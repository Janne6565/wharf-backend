package com.wharf.backend.integration;

import com.wharf.backend.configuration.Profiles;
import com.wharf.backend.entity.OAuthIdentityEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.core.OAuthProvider;
import com.wharf.backend.repository.DeviceCodeRepository;
import com.wharf.backend.repository.OAuthIdentityRepository;
import com.wharf.backend.repository.ProjectInviteRepository;
import com.wharf.backend.repository.ProjectMemberRepository;
import com.wharf.backend.repository.ProjectRepository;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.repository.VaultRepository;
import com.wharf.backend.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Self-service account deletion end to end, through the real filter chain and the
 * Flyway-migrated H2 schema — so the foreign keys and the ON DELETE CASCADEs the service
 * deliberately leans on are the real ones.
 *
 * <p>The interesting cases are the destructive ones: an owned project is deleted even when
 * other people are still in it, and a rejected re-authentication must leave <em>everything</em>
 * standing (the deletion is one transaction precisely so that holds).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles(Profiles.TEST)
class AccountDeletionFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VaultRepository vaultRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectMemberRepository memberRepository;
    @Autowired
    private ProjectInviteRepository inviteRepository;
    @Autowired
    private OAuthIdentityRepository oauthIdentityRepository;
    @Autowired
    private DeviceCodeRepository deviceCodeRepository;

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

    /** Registers an account, marks its address verified and logs in, returning the access token. */
    private String register(String email) throws Exception {
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email)
                .put("authKey", "auth-" + email)
                .put("recoveryAuthKey", "recovery-" + email)
                .put("vault", base64("personal-vault")));
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        user.setEmailVerified(true);
        userRepository.saveAndFlush(user);

        String loginBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email).put("authKey", "auth-" + email).put("tokenMode", "DIRECT"));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).path("accessToken").asText();
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

    /** Creates a project owned by the token's account and returns its id. */
    private UUID createProject(String token, String name) throws Exception {
        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("name", name).put("description", "d")
                .put("vault", base64("proj-" + name)).put("wrappedDek", bytes(80, 1)));
        MvcResult created = mockMvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(created).path("id").asText());
    }

    /** Invites {@code email} to the project and immediately accepts it as {@code inviteeToken}. */
    private void inviteAndAccept(String ownerToken, UUID projectId, String email, String inviteeToken)
            throws Exception {
        String inviteBody = objectMapper.writeValueAsString(objectMapper.createObjectNode().put("email", email));
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/invites")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(inviteBody))
                .andExpect(status().isCreated());

        MvcResult invites = mockMvc.perform(get("/api/v1/users/me/invites")
                        .header("Authorization", bearer(inviteeToken)))
                .andExpect(status().isOk())
                .andReturn();
        String inviteId = json(invites).get(0).path("id").asText();
        mockMvc.perform(post("/api/v1/users/me/invites/" + inviteId + "/accept")
                        .header("Authorization", bearer(inviteeToken)))
                .andExpect(status().isOk());
    }

    private UUID userId(String email) {
        return userRepository.findByEmail(email).orElseThrow().getId();
    }

    private String deleteBody(String authKey) throws Exception {
        return objectMapper.writeValueAsString(objectMapper.createObjectNode().put("authKey", authKey));
    }

    // ---- the happy path -----------------------------------------------------------------

    @Test
    void deletion_removesTheAccountAndEverythingKeyedToIt() throws Exception {
        String token = register("del-owner@acme.io");
        UUID id = userId("del-owner@acme.io");
        publishPublicKey(token, 10);
        UUID projectId = createProject(token, "Solo");

        // Give the account the remaining kinds of dependent row a real account accumulates.
        oauthIdentityRepository.saveAndFlush(OAuthIdentityEntity.builder()
                .id(UUID.randomUUID())
                .userId(id)
                .provider(OAuthProvider.GOOGLE)
                .subject("google-sub-" + id)
                .emailAtLink("del-owner@acme.io")
                .createdAt(Instant.now())
                .build());
        mockMvc.perform(post("/api/v1/device-codes").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteBody("auth-del-owner@acme.io")))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(id)).isEmpty();
        assertThat(vaultRepository.findByUserId(id)).isEmpty();
        assertThat(memberRepository.findByUserId(id)).isEmpty();
        assertThat(oauthIdentityRepository.findByUserId(id)).isEmpty();
        assertThat(deviceCodeRepository.findByUserId(id)).isEmpty();
        assertThat(projectRepository.findById(projectId)).isEmpty();
    }

    @Test
    void deletion_destroysOwnedProjectsEvenWhenOthersAreStillMembers() throws Exception {
        String ownerToken = register("del-boss@acme.io");
        String memberToken = register("del-crew@acme.io");
        UUID ownerId = userId("del-boss@acme.io");
        UUID memberId = userId("del-crew@acme.io");
        publishPublicKey(ownerToken, 11);

        UUID projectId = createProject(ownerToken, "Shared");
        inviteAndAccept(ownerToken, projectId, "del-crew@acme.io", memberToken);
        assertThat(memberRepository.findByProjectId(projectId)).hasSize(2);

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteBody("auth-del-boss@acme.io")))
                .andExpect(status().isNoContent());

        // The project is gone outright — the surviving member's row went with it.
        assertThat(projectRepository.findById(projectId)).isEmpty();
        assertThat(memberRepository.findByProjectId(projectId)).isEmpty();
        assertThat(memberRepository.findByUserId(memberId)).isEmpty();
        // ...but the member's own account is untouched.
        assertThat(userRepository.findById(memberId)).isPresent();
        assertThat(userRepository.findById(ownerId)).isEmpty();
    }

    @Test
    void deletion_leavesProjectsWhereTheCallerIsOnlyAMember() throws Exception {
        String ownerToken = register("del-keeper@acme.io");
        String memberToken = register("del-leaver@acme.io");
        UUID ownerId = userId("del-keeper@acme.io");
        UUID memberId = userId("del-leaver@acme.io");
        publishPublicKey(ownerToken, 12);

        UUID projectId = createProject(ownerToken, "Kept");
        inviteAndAccept(ownerToken, projectId, "del-leaver@acme.io", memberToken);

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", bearer(memberToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteBody("auth-del-leaver@acme.io")))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(memberId)).isEmpty();
        assertThat(projectRepository.findById(projectId)).isPresent();
        assertThat(memberRepository.findByProjectId(projectId))
                .singleElement()
                .satisfies(remaining -> assertThat(remaining.getUserId()).isEqualTo(ownerId));
    }

    // ---- re-authentication --------------------------------------------------------------

    @Test
    void deletion_withWrongAuthKey_is401AndRollsBackEverything() throws Exception {
        String token = register("del-wrongkey@acme.io");
        UUID id = userId("del-wrongkey@acme.io");
        publishPublicKey(token, 13);
        UUID projectId = createProject(token, "Survivor");

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteBody("auth-somebody-else")))
                .andExpect(status().isUnauthorized());

        // Nothing may have been removed: the whole deletion is a single transaction.
        assertThat(userRepository.findById(id)).isPresent();
        assertThat(vaultRepository.findByUserId(id)).isPresent();
        assertThat(projectRepository.findById(projectId)).isPresent();
        assertThat(memberRepository.findByUserId(id)).hasSize(1);
    }

    @Test
    void deletion_withoutAuthKeyOnAPasswordAccount_is400WithCode() throws Exception {
        String token = register("del-nokey@acme.io");
        UUID id = userId("del-nokey@acme.io");

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("auth_key_required"));

        assertThat(userRepository.findById(id)).isPresent();
    }

    @Test
    void deletion_ofAnOAuthOnlyAccount_needsNothingBeyondTheSession() throws Exception {
        // An account created via OAuth has no password hash, so there is no second factor to
        // ask for — the valid session has to be enough.
        UserEntity oauthUser = userRepository.saveAndFlush(UserEntity.builder()
                .id(UUID.randomUUID())
                .email("del-oauth@acme.io")
                .authKeyHash(null)
                .recoveryKeyHash(null)
                .emailVerified(true)
                .tokenVersion(0)
                .createdAt(Instant.now())
                .build());
        String token = jwtService.issueIdentityToken(oauthUser);

        mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(oauthUser.getId())).isEmpty();
    }

    // ---- aftermath ----------------------------------------------------------------------

    @Test
    void deletion_freesTheEmailForReRegistration() throws Exception {
        String email = "del-recycled@acme.io";
        String token = register(email);

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteBody("auth-" + email)))
                .andExpect(status().isNoContent());

        String body = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("email", email)
                .put("authKey", "auth2-" + email)
                .put("recoveryAuthKey", "recovery2-" + email)
                .put("vault", base64("fresh-vault")));
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        // A brand-new, unverified account — not a resurrection of the old one.
        UserEntity reborn = userRepository.findByEmail(email).orElseThrow();
        assertThat(reborn.isEmailVerified()).isFalse();
    }

    // ---- preview ------------------------------------------------------------------------

    @Test
    void deletionPreview_reportsOwnedProjectsAndOtherMemberships() throws Exception {
        String aToken = register("del-prev-a@acme.io");
        String bToken = register("del-prev-b@acme.io");
        publishPublicKey(aToken, 14);
        publishPublicKey(bToken, 15);

        UUID aProject = createProject(aToken, "A-owned");
        inviteAndAccept(aToken, aProject, "del-prev-b@acme.io", bToken);
        createProject(bToken, "B-owned");

        // A owns one project that one other person would lose, and is a member of nothing else.
        mockMvc.perform(get("/api/v1/users/me/deletion-preview").header("Authorization", bearer(aToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownedProjects.length()").value(1))
                .andExpect(jsonPath("$.ownedProjects[0].id").value(aProject.toString()))
                .andExpect(jsonPath("$.ownedProjects[0].name").value("A-owned"))
                .andExpect(jsonPath("$.ownedProjects[0].otherMemberCount").value(1))
                .andExpect(jsonPath("$.otherMemberships").value(0));

        // B owns a project nobody else is in, and merely belongs to A's.
        mockMvc.perform(get("/api/v1/users/me/deletion-preview").header("Authorization", bearer(bToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownedProjects.length()").value(1))
                .andExpect(jsonPath("$.ownedProjects[0].name").value("B-owned"))
                .andExpect(jsonPath("$.ownedProjects[0].otherMemberCount").value(0))
                .andExpect(jsonPath("$.otherMemberships").value(1));
    }

    @Test
    void deletion_alsoRemovesInvitesAddressedToTheAccount() throws Exception {
        String ownerToken = register("del-inviter@acme.io");
        String inviteeToken = register("del-invitee@acme.io");
        UUID inviteeId = userId("del-invitee@acme.io");
        publishPublicKey(ownerToken, 16);
        UUID projectId = createProject(ownerToken, "Pending");

        String inviteBody = objectMapper.writeValueAsString(
                objectMapper.createObjectNode().put("email", "del-invitee@acme.io"));
        mockMvc.perform(post("/api/v1/projects/" + projectId + "/invites")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(inviteBody))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", bearer(inviteeToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteBody("auth-del-invitee@acme.io")))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(inviteeId)).isEmpty();
        assertThat(inviteRepository.findByEmail("del-invitee@acme.io")).isEmpty();
        assertThat(projectRepository.findById(projectId)).isPresent();
    }
}
