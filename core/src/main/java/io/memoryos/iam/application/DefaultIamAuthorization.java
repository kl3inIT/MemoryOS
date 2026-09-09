package io.memoryos.iam.application;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.Authority;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.persistence.IamAuthorizationRepository;
import io.memoryos.iam.persistence.IamAuthorizationRepository.AuthorizationSnapshot;
import io.memoryos.iam.persistence.IamLockRepository;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultIamAuthorization implements IamAuthorization {
    private static final Set<IamCapability> SCOPED_CAPABILITIES = Set.of(
            IamCapability.GROUPS_READ,
            IamCapability.GROUPS_MANAGE,
            IamCapability.SOURCES_READ,
            IamCapability.SOURCES_MANAGE
    );

    private final IamAuthorizationRepository authorityRepository;
    private final IamLockRepository lockRepository;

    public DefaultIamAuthorization(
            IamAuthorizationRepository authorityRepository,
            IamLockRepository lockRepository
    ) {
        this.authorityRepository = Objects.requireNonNull(
                authorityRepository,
                "authorityRepository must not be null"
        );
        this.lockRepository = Objects.requireNonNull(lockRepository, "lockRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public Set<IamCapability> effectiveCapabilities(ActorId actorId) {
        return snapshot(actorId)
                .map(AuthorizationSnapshot::explicitCapabilities)
                .map(IamCapability::expand)
                .orElseGet(Set::of);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<IamCapability> scopedCapabilities(ActorId actorId) {
        return snapshot(actorId).map(snapshot -> {
            if (!snapshot.managesOrdinaryGroup()) {
                return Set.<IamCapability>of();
            }
            EnumSet<IamCapability> scoped = EnumSet.copyOf(SCOPED_CAPABILITIES);
            scoped.removeAll(IamCapability.expand(snapshot.explicitCapabilities()));
            return scoped.isEmpty() ? Set.<IamCapability>of() : Collections.unmodifiableSet(scoped);
        }).orElseGet(Set::of);
    }
    @Override
    @Transactional(readOnly = true)
    public long authorizationVersion(ActorId actorId) {
        return snapshot(actorId).map(AuthorizationSnapshot::authorizationVersion).orElse(0L);
    }


    @Override
    @Transactional(readOnly = true)
    public IamAccess require(ActorId actorId, IamCapability capability, boolean allowScoped) {
        return require(snapshotOrDenied(actorId), capability, allowScoped);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IamAccess lockAndRequire(ActorId actorId, IamCapability capability, boolean allowScoped) {
        AuthorizationSnapshot beforeLock = snapshotOrDenied(actorId);
        lockRepository.lockTenantShared(beforeLock.tenantId());
        return requireSameTenant(actorId, beforeLock.tenantId(), capability, allowScoped);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IamAccess lockAndRequireExclusive(ActorId actorId, IamCapability capability) {
        AuthorizationSnapshot beforeLock = snapshotOrDenied(actorId);
        lockRepository.lockTenant(beforeLock.tenantId());
        return requireSameTenant(actorId, beforeLock.tenantId(), capability, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public IamAccess lockAndRequireAdministration(ActorId actorId) {
        return lockAndRequireExclusive(actorId, IamCapability.IAM_ADMIN);
    }

    private IamAccess requireSameTenant(
            ActorId actorId,
            TenantId lockedTenantId,
            IamCapability capability,
            boolean allowScoped
    ) {
        AuthorizationSnapshot afterLock = snapshotOrDenied(actorId);
        if (!lockedTenantId.equals(afterLock.tenantId())) {
            throw denied(actorId, capability);
        }
        return require(afterLock, capability, allowScoped);
    }

    private static IamAccess require(
            AuthorizationSnapshot snapshot,
            IamCapability capability,
            boolean allowScoped
    ) {
        IamCapability requiredCapability = Objects.requireNonNull(capability, "capability must not be null");
        if (IamCapability.expand(snapshot.explicitCapabilities()).contains(requiredCapability)) {
            return new IamAccess(snapshot.tenantId(), Authority.GLOBAL);
        }
        if (allowScoped
                && snapshot.managesOrdinaryGroup()
                && SCOPED_CAPABILITIES.contains(requiredCapability)) {
            return new IamAccess(snapshot.tenantId(), Authority.SCOPED);
        }
        throw denied(null, requiredCapability);
    }

    private java.util.Optional<AuthorizationSnapshot> snapshot(ActorId actorId) {
        return authorityRepository.find(Objects.requireNonNull(actorId, "actorId must not be null"));
    }

    private AuthorizationSnapshot snapshotOrDenied(ActorId actorId) {
        return snapshot(actorId).orElseThrow(() -> denied(actorId, null));
    }

    private static IamException denied(ActorId actorId, IamCapability capability) {
        return new IamException(
                IamFailureReason.ACCESS_DENIED,
                "IAM authority denied for actor=" + actorId + ", capability=" + capability
        );
    }
}
