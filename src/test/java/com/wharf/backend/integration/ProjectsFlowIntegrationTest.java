package com.wharf.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wharf.backend.configuration.Profiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end team-project flow through the real filter chain and Flyway-migrated H2 schema:
 * two users, public-key publishing, project creation, invite/accept, key distribution,
 * project-vault read/write with a version conflict, DEK rotation with member removal, and
 * the non-member 404 / role-enforcement / ownership-transfer / leave rules.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(Profiles.TEST)
class ProjectsFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String base64(String plain) {
        return Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** Deterministic base64 of {@code n} bytes filled with {@code fill} (crypto is opaque to the server). */
    private String bytes(int n, int fill) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) fill);
        return Base64.getEncoder().encodeToString(b);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    /** Registers an account in DIRECT mode and returns its access token. */
    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email)
                .put("authKey", "auth-" + email)
                .put("recoveryAuthKey", "recovery-" + email)
                .put("vault", base64("personal-vault"))
                .put("tokenMode", "DIRECT"));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).path("tokens").path("accessToken").asText();
    }

    private String myUserId(String token) throws Exception {
        MvcResult me = mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return json(me).path("id").asText();
    }

    private void publishPublicKey(String token, int fill) throws Exception {
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("publicKey", bytes(32, fill))
                .put("rotate", false));
        mockMvc.perform(put("/api/v1/users/me/public-key")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    void fullProjectLifecycle() throws Exception {
        String aToken = register("alice@acme.io");
        String bToken = register("bob@acme.io");
        String aUserId = myUserId(aToken);

        // A must publish a public key before creating a project (412 otherwise).
        String noKeyCreate = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("name", "Acme").put("description", "d")
                .put("vault", base64("proj-v1")).put("wrappedDek", bytes(80, 1)));
        mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(aToken))
                        .contentType(MediaType.APPLICATION_JSON).content(noKeyCreate))
                .andExpect(status().isPreconditionFailed());

        publishPublicKey(aToken, 10);

        // Create the project (vault v1, owner keyed).
        MvcResult created = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(aToken))
                        .contentType(MediaType.APPLICATION_JSON).content(noKeyCreate))
                .andExpect(status().isCreated())
                .andReturn();
        String projectId = json(created).path("id").asText();
        assertThat(json(created).path("vaultVersion").asLong()).isEqualTo(1L);
        assertThat(json(created).path("role").asText()).isEqualTo("OWNER");

        // Invite B by email.
        String inviteBody = objectMapper.writeValueAsString(objectMapper.createObjectNode().put("email", "BOB@acme.io"));
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/invites")
                        .header("Authorization", bearer(aToken))
                        .contentType(MediaType.APPLICATION_JSON).content(inviteBody))
                .andExpect(status().isCreated());

        // A duplicate invite is a conflict.
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/invites")
                        .header("Authorization", bearer(aToken))
                        .contentType(MediaType.APPLICATION_JSON).content(inviteBody))
                .andExpect(status().isConflict());

        // B sees the invite and accepts it.
        MvcResult bInvites = mockMvc.perform(get("/api/v1/users/me/invites").header("Authorization", bearer(bToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode invites = json(bInvites);
        assertThat(invites).hasSize(1);
        assertThat(invites.get(0).path("projectName").asText()).isEqualTo("Acme");
        assertThat(invites.get(0).path("invitedByEmail").asText()).isEqualTo("alice@acme.io");
        String inviteId = invites.get(0).path("id").asText();

        MvcResult accepted = mockMvc.perform(post("/api/v1/users/me/invites/" + inviteId + "/accept")
                        .header("Authorization", bearer(bToken)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(accepted).path("awaitingKey").asBoolean()).isTrue();

        // B publishes their public key; A now sees B in pending-keys.
        publishPublicKey(bToken, 20);
        MvcResult pending = mockMvc.perform(get("/api/v1/projects/" + projectId + "/pending-keys")
                        .header("Authorization", bearer(aToken)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(pending)).hasSize(1);
        String bUserId = json(pending).get(0).path("userId").asText();
        assertThat(json(pending).get(0).path("email").asText()).isEqualTo("bob@acme.io");

        // A seals the DEK to B's key (against vault version 1).
        String keyBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("wrappedDek", bytes(80, 2)).put("vaultVersion", 1));
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/members/" + bUserId + "/key")
                        .header("Authorization", bearer(aToken))
                        .contentType(MediaType.APPLICATION_JSON).content(keyBody))
                .andExpect(status().isNoContent());

        // B can now read the vault and holds a wrapped DEK.
        MvcResult bVault = mockMvc.perform(get("/api/v1/projects/" + projectId + "/vault")
                        .header("Authorization", bearer(bToken)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(bVault).path("wrappedDek").isNull()).isFalse();
        assertThat(json(bVault).path("version").asLong()).isEqualTo(1L);

        // B writes the vault (v1 -> v2).
        String putBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("vault", base64("proj-v2")).put("expectedVersion", 1));
        MvcResult putV2 = mockMvc.perform(put("/api/v1/projects/" + projectId + "/vault")
                        .header("Authorization", bearer(bToken))
                        .contentType(MediaType.APPLICATION_JSON).content(putBody))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(putV2).path("version").asLong()).isEqualTo(2L);

        // Stale expectedVersion -> 409.
        mockMvc.perform(put("/api/v1/projects/" + projectId + "/vault")
                        .header("Authorization", bearer(bToken))
                        .contentType(MediaType.APPLICATION_JSON).content(putBody))
                .andExpect(status().isConflict());

        // A's wrapped DEK before rotation.
        MvcResult aVaultBefore = mockMvc.perform(get("/api/v1/projects/" + projectId + "/vault")
                        .header("Authorization", bearer(aToken)))
                .andExpect(status().isOk())
                .andReturn();
        String aWrappedBefore = json(aVaultBefore).path("wrappedDek").asText();

        // A rotates the DEK, removing B and re-keying only themselves (vault v2 -> v3).
        ObjectNode rotate = objectMapper.createObjectNode();
        rotate.put("removeUserId", bUserId);
        rotate.put("vault", base64("proj-v3"));
        rotate.put("expectedVersion", 2);
        ArrayNode wrappedKeys = rotate.putArray("wrappedKeys");
        ObjectNode aKey = wrappedKeys.addObject();
        aKey.put("userId", aUserId);
        aKey.put("wrappedDek", bytes(80, 9));
        MvcResult rotated = mockMvc.perform(post("/api/v1/projects/" + projectId + "/rotate")
                        .header("Authorization", bearer(aToken))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(rotate)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(rotated).path("version").asLong()).isEqualTo(3L);

        // B no longer lists the project and a direct GET is 404 (no existence leak).
        MvcResult bProjects = mockMvc.perform(get("/api/v1/projects").header("Authorization", bearer(bToken)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(bProjects)).isEmpty();
        mockMvc.perform(get("/api/v1/projects/" + projectId).header("Authorization", bearer(bToken)))
                .andExpect(status().isNotFound());

        // A's wrapped DEK changed as part of the rotation.
        MvcResult aVaultAfter = mockMvc.perform(get("/api/v1/projects/" + projectId + "/vault")
                        .header("Authorization", bearer(aToken)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(aVaultAfter).path("wrappedDek").asText()).isNotEqualTo(aWrappedBefore);
    }

    @Test
    void roleEnforcementTransferAndLeave() throws Exception {
        String aToken = register("owner@acme.io");
        String bToken = register("member@acme.io");
        String cToken = register("outsider@acme.io");
        publishPublicKey(aToken, 30);

        String createBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("name", "Team").put("vault", base64("v1")).put("wrappedDek", bytes(80, 1)));
        MvcResult created = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(aToken))
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andReturn();
        String projectId = json(created).path("id").asText();

        // Invite + accept so B is a MEMBER.
        String inviteBody = objectMapper.writeValueAsString(objectMapper.createObjectNode().put("email", "member@acme.io"));
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/invites")
                        .header("Authorization", bearer(aToken))
                        .contentType(MediaType.APPLICATION_JSON).content(inviteBody))
                .andExpect(status().isCreated());
        String inviteId = json(mockMvc.perform(get("/api/v1/users/me/invites").header("Authorization", bearer(bToken)))
                .andExpect(status().isOk()).andReturn()).get(0).path("id").asText();
        mockMvc.perform(post("/api/v1/users/me/invites/" + inviteId + "/accept").header("Authorization", bearer(bToken)))
                .andExpect(status().isOk());
        String bUserId = myUserId(bToken);

        // Non-member C gets 404 (never 403) on a project-scoped read.
        mockMvc.perform(get("/api/v1/projects/" + projectId).header("Authorization", bearer(cToken)))
                .andExpect(status().isNotFound());

        // B (MEMBER) cannot edit metadata (403) or delete (403).
        String patchBody = objectMapper.writeValueAsString(objectMapper.createObjectNode().put("name", "Renamed"));
        mockMvc.perform(patch("/api/v1/projects/" + projectId)
                        .header("Authorization", bearer(bToken))
                        .contentType(MediaType.APPLICATION_JSON).content(patchBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/projects/" + projectId).header("Authorization", bearer(bToken)))
                .andExpect(status().isForbidden());

        // Owner A transfers ownership to B; A is atomically demoted to ADMIN.
        String roleBody = objectMapper.writeValueAsString(objectMapper.createObjectNode().put("role", "OWNER"));
        mockMvc.perform(patch("/api/v1/projects/" + projectId + "/members/" + bUserId)
                        .header("Authorization", bearer(aToken))
                        .contentType(MediaType.APPLICATION_JSON).content(roleBody))
                .andExpect(status().isNoContent());

        MvcResult detail = mockMvc.perform(get("/api/v1/projects/" + projectId).header("Authorization", bearer(bToken)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(json(detail).path("role").asText()).isEqualTo("OWNER");

        // A (now ADMIN) can no longer delete the project (owner-only).
        mockMvc.perform(delete("/api/v1/projects/" + projectId).header("Authorization", bearer(aToken)))
                .andExpect(status().isForbidden());

        // The new owner B cannot leave without transferring first; the ADMIN A can.
        mockMvc.perform(delete("/api/v1/projects/" + projectId + "/members/me").header("Authorization", bearer(bToken)))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/projects/" + projectId + "/members/me").header("Authorization", bearer(aToken)))
                .andExpect(status().isNoContent());

        // A is gone: their project list no longer includes it.
        assertThat(json(mockMvc.perform(get("/api/v1/projects").header("Authorization", bearer(aToken)))
                .andExpect(status().isOk()).andReturn())).isEmpty();
    }
}
