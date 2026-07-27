package com.wharf.backend.services.user;

import com.wharf.backend.entity.ProjectEntity;
import com.wharf.backend.entity.ProjectMemberEntity;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.action.DeleteAccountRequest;
import com.wharf.backend.model.core.AccountDeletionPreviewResponse;
import com.wharf.backend.model.core.AccountDeletionPreviewResponse.OwnedProjectImpact;
import com.wharf.backend.model.core.ProjectRole;
import com.wharf.backend.model.exception.InvalidCredentialsException;
import com.wharf.backend.model.exception.PasswordRequiredForDeletionException;
import com.wharf.backend.model.exception.UserNotFoundException;
import com.wharf.backend.repository.DeviceCodeRepository;
import com.wharf.backend.repository.OAuthIdentityRepository;
import com.wharf.backend.repository.ProjectInviteRepository;
import com.wharf.backend.repository.ProjectMemberRepository;
import com.wharf.backend.repository.ProjectRepository;
import com.wharf.backend.repository.ProjectVaultRepository;
import com.wharf.backend.repository.UserRepository;
import com.wharf.backend.repository.VaultRepository;
import com.wharf.backend.services.auth.SecurityNotificationService;
import com.wharf.backend.services.mail.ActorContext;
import com.wharf.backend.services.mail.ActorContextResolver;
import com.wharf.backend.services.project.ProjectNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Self-service account deletion, and the read-only preview a client shows before asking for
 * confirmation.
 *
 * <p><strong>Deletion is irreversible.</strong> The server is zero-knowledge — it holds only
 * ciphertext and bcrypt hashes — so there is nothing to restore from and no support path
 * that could bring an account back. It is also deliberately destructive to third parties:
 * every project the account <em>owns</em> is deleted outright, <em>including projects that
 * still have other members</em>. That is the chosen product behaviour (an owner leaving
 * without transferring ownership leaves an unadministrable project behind), not an
 * oversight, which is why the whole thing runs as one transaction and is logged at INFO
 * with the collateral it caused.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final ProjectInviteRepository inviteRepository;
    private final ProjectVaultRepository projectVaultRepository;
    private final OAuthIdentityRepository oauthIdentityRepository;
    private final DeviceCodeRepository deviceCodeRepository;
    private final VaultRepository vaultRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityNotificationService securityNotifications;
    private final ProjectNotificationService projectNotifications;
    private final ActorContextResolver actorContextResolver;

    public AccountDeletionService(UserRepository userRepository,
                                  ProjectRepository projectRepository,
                                  ProjectMemberRepository memberRepository,
                                  ProjectInviteRepository inviteRepository,
                                  ProjectVaultRepository projectVaultRepository,
                                  OAuthIdentityRepository oauthIdentityRepository,
                                  DeviceCodeRepository deviceCodeRepository,
                                  VaultRepository vaultRepository,
                                  PasswordEncoder passwordEncoder,
                                  SecurityNotificationService securityNotifications,
                                  ProjectNotificationService projectNotifications,
                                  ActorContextResolver actorContextResolver) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.inviteRepository = inviteRepository;
        this.projectVaultRepository = projectVaultRepository;
        this.oauthIdentityRepository = oauthIdentityRepository;
        this.deviceCodeRepository = deviceCodeRepository;
        this.vaultRepository = vaultRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityNotifications = securityNotifications;
        this.projectNotifications = projectNotifications;
        this.actorContextResolver = actorContextResolver;
    }

    /**
     * What {@link #deleteAccount} would destroy, so the UI can name the projects and the
     * number of people that go with them instead of showing a vague warning.
     */
    @Transactional(readOnly = true)
    public AccountDeletionPreviewResponse previewDeletion(UUID userId) {
        List<ProjectMemberEntity> memberships = memberRepository.findByUserId(userId);
        List<UUID> ownedProjectIds = ownedProjectIds(memberships);
        Map<UUID, ProjectEntity> projects = projectRepository.findAllById(ownedProjectIds).stream()
                .collect(Collectors.toMap(ProjectEntity::getId, Function.identity()));

        List<OwnedProjectImpact> owned = new ArrayList<>(ownedProjectIds.size());
        for (UUID projectId : ownedProjectIds) {
            ProjectEntity project = projects.get(projectId);
            // The caller is a member of their own project, so everyone *else* is one fewer.
            long otherMembers = memberRepository.countByProjectId(projectId) - 1;
            owned.add(new OwnedProjectImpact(projectId,
                    project == null ? null : project.getName(),
                    (int) otherMembers));
        }

        return new AccountDeletionPreviewResponse(owned, memberships.size() - owned.size());
    }

    /**
     * Erase the account and everything hanging off it. One transaction, all-or-nothing: a
     * partial deletion would strand other people's projects in a half-removed state, so a
     * failure anywhere leaves the account exactly as it was (which is what the wrong-authKey
     * path relies on).
     */
    @Transactional
    public void deleteAccount(UUID userId, DeleteAccountRequest request) {
        // Re-read rather than trusting the authenticated principal: the entity attached to
        // this transaction is the one we are about to delete, and its hash must be current.
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        requireReauthentication(user, request);

        List<ProjectMemberEntity> memberships = memberRepository.findByUserId(userId);
        List<UUID> ownedProjectIds = ownedProjectIds(memberships);
        String actorEmail = user.getEmail();

        // Read who is about to lose what *before* anything is deleted. Afterwards the rows
        // that name them are gone, and there is no second chance to work out who to tell.
        List<DoomedProject> doomed = collectDoomedProjects(ownedProjectIds, userId);

        // 1. Owned projects go entirely — members, invites, vault, then the project row.
        int otherMembersEvicted = 0;
        for (UUID projectId : ownedProjectIds) {
            List<ProjectMemberEntity> projectMembers = memberRepository.findByProjectId(projectId);
            otherMembersEvicted += projectMembers.size() - 1; // everyone but the departing owner
            memberRepository.deleteAll(projectMembers);
            inviteRepository.deleteAll(inviteRepository.findByProjectId(projectId));
            projectVaultRepository.findByProjectId(projectId).ifPresent(projectVaultRepository::delete);

            // The children above are deleted explicitly rather than left to the ON DELETE
            // CASCADE on projects, and flushed before the parent: Hibernate orders its
            // pending deletes by entity type, not by foreign key, so without this flush the
            // project row can be issued ahead of its members and trip the constraint.
            memberRepository.flush();
            projectRepository.deleteById(projectId);
        }
        projectRepository.flush();

        // 2. Memberships in projects owned by someone else — those projects survive, the
        //    caller simply stops being part of them.
        memberRepository.deleteAll(memberRepository.findByUserId(userId));

        // 3. Invites addressed to this account's address, so a re-registration of the same
        //    email does not inherit invitations aimed at the deleted account. (Invites the
        //    user *sent* into other people's projects are left to the ON DELETE CASCADE on
        //    project_invites.invited_by, which fires when the user row goes in step 5.)
        inviteRepository.deleteAll(inviteRepository.findByEmail(user.getEmail()));

        // 4. Everything else keyed to the account.
        oauthIdentityRepository.deleteAll(oauthIdentityRepository.findByUserId(userId));
        deviceCodeRepository.deleteAll(deviceCodeRepository.findByUserId(userId));
        vaultRepository.findByUserId(userId).ifPresent(vaultRepository::delete);
        // email_verifications is deliberately not touched here: its FK is ON DELETE CASCADE
        // and there is at most one row per account, so the database removes it in step 5.
        vaultRepository.flush();

        // 5. Finally the account itself, which frees the email for re-registration.
        userRepository.delete(user);
        userRepository.flush();

        log.info("Deleted account {} — {} owned project(s) destroyed, {} other member(s) lost access",
                userId, ownedProjectIds.size(), otherMembersEvicted);

        // Notifications last, once the destructive work has succeeded. They are enqueued on the
        // mail executor rather than sent here, so a slow mail service cannot hold this
        // transaction open; the trade-off is that a rollback after this point would leave
        // mails already in flight, which is why nothing else happens below.
        ActorContext actor = actorContextResolver.resolve();
        securityNotifications.notifyAccountDeleted(actorEmail, ownedProjectIds.size(), actor);
        for (DoomedProject project : doomed) {
            // From an evicted member's side there is no difference between the owner deleting
            // the project and the owner deleting themselves, so they get the same mail.
            project.memberEmails().forEach(member ->
                    projectNotifications.notifyProjectDeleted(member, project.name(), actorEmail, actor));
        }
    }

    /**
     * The projects about to be destroyed and the addresses of everyone <em>other than</em> the
     * departing owner who is in them. Must be called before the deletion begins.
     */
    private List<DoomedProject> collectDoomedProjects(List<UUID> ownedProjectIds, UUID departingUserId) {
        List<DoomedProject> doomed = new ArrayList<>(ownedProjectIds.size());
        for (UUID projectId : ownedProjectIds) {
            List<UUID> otherMemberIds = memberRepository.findByProjectId(projectId).stream()
                    .map(ProjectMemberEntity::getUserId)
                    .filter(memberId -> !memberId.equals(departingUserId))
                    .toList();
            if (otherMemberIds.isEmpty()) {
                continue;
            }
            String name = projectRepository.findById(projectId).map(ProjectEntity::getName).orElse(null);
            if (name == null) {
                continue;
            }
            doomed.add(new DoomedProject(name, userRepository.findAllById(otherMemberIds).stream()
                    .map(UserEntity::getEmail)
                    .toList()));
        }
        return doomed;
    }

    /** A project that is about to be destroyed, and who needs telling. */
    private record DoomedProject(String name, List<String> memberEmails) {
    }

    /**
     * Deleting an account is destructive and irreversible, so a valid access token is not
     * sufficient on its own: an account with a master password must re-prove it here, which
     * means a stolen token or an unattended session cannot destroy the account.
     *
     * <p>An OAuth-only account ({@code authKeyHash == null}) has no password to re-prove and
     * no other second factor available, so the valid session is all we can ask for — the
     * supplied {@code authKey}, if any, is simply meaningless there. No timing equalisation
     * is needed on this path (unlike login): the caller is already authenticated as this
     * exact account, so there is nothing to enumerate.
     */
    private void requireReauthentication(UserEntity user, DeleteAccountRequest request) {
        if (user.getAuthKeyHash() == null) {
            return;
        }
        if (request == null || !request.hasAuthKey()) {
            throw new PasswordRequiredForDeletionException();
        }
        if (!passwordEncoder.matches(request.authKey(), user.getAuthKeyHash())) {
            throw new InvalidCredentialsException();
        }
    }

    private static List<UUID> ownedProjectIds(List<ProjectMemberEntity> memberships) {
        return memberships.stream()
                .filter(membership -> membership.getRole() == ProjectRole.OWNER)
                .map(ProjectMemberEntity::getProjectId)
                .toList();
    }
}
