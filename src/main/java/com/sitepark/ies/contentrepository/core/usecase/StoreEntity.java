package com.sitepark.ies.contentrepository.core.usecase;

import java.util.Optional;

import com.sitepark.ies.contentrepository.core.domain.entity.ChangeSet;
import com.sitepark.ies.contentrepository.core.domain.entity.Entity;
import com.sitepark.ies.contentrepository.core.domain.entity.EntityLock;
import com.sitepark.ies.contentrepository.core.domain.entity.HistoryEntryType;
import com.sitepark.ies.contentrepository.core.domain.entity.Identifier;
import com.sitepark.ies.contentrepository.core.domain.exception.AccessDenied;
import com.sitepark.ies.contentrepository.core.domain.exception.EntityLocked;
import com.sitepark.ies.contentrepository.core.domain.exception.EntityNotFound;
import com.sitepark.ies.contentrepository.core.domain.exception.ParentMissing;
import com.sitepark.ies.contentrepository.core.domain.service.ContentDiffer;
import com.sitepark.ies.contentrepository.core.port.AccessControl;
import com.sitepark.ies.contentrepository.core.port.ContentRepository;
import com.sitepark.ies.contentrepository.core.port.EntityLockManager;
import com.sitepark.ies.contentrepository.core.port.HistoryManager;
import com.sitepark.ies.contentrepository.core.port.IdGenerator;
import com.sitepark.ies.contentrepository.core.port.SearchIndex;
import com.sitepark.ies.contentrepository.core.port.VersioningManager;

public final class StoreEntity {

	private final ContentRepository repository;
	private final EntityLockManager lockManager;
	private final VersioningManager versioningManager;
	private final HistoryManager historyManager;
	private final AccessControl accessControl;
	private final IdGenerator idGenerator;
	private final SearchIndex searchIndex;
	private final ContentDiffer contentDiffer;

	protected StoreEntity(ContentRepository repository, EntityLockManager lockManager,
			VersioningManager versioningManager, HistoryManager historyManager, AccessControl accessControl,
			IdGenerator idGenerator, SearchIndex searchIndex, ContentDiffer contentDiffer) {
		this.repository = repository;
		this.lockManager = lockManager;
		this.versioningManager = versioningManager;
		this.historyManager = historyManager;
		this.accessControl = accessControl;
		this.idGenerator = idGenerator;
		this.searchIndex = searchIndex;
		this.contentDiffer = contentDiffer;
	}

	public Identifier store(Entity entity) {
		if (entity.getIdentifier().isEmpty()) {
			return this.create(entity);
		} else {
			return this.update(entity);
		}
	}

	private Identifier create(Entity newEntity) {

		Optional<Identifier> parent = newEntity.getParent();
		parent.orElseThrow(() -> new ParentMissing());

		long parentId = this.repository.resolve(parent.get());

		if (!this.accessControl.isEntityCreateable(parentId)) {
			throw new AccessDenied("Not allowed to create entity in group " + parent);
		}

		long generatedId = this.idGenerator.generate();

		Entity entityWithId = newEntity.toBuilder().identifier(Identifier.ofId(generatedId)).build();

		Entity versioned = this.versioningManager.createNewVersion(entityWithId);

		this.repository.store(versioned);
		this.historyManager.createEntry(generatedId, versioned.getVersion().get().getTimestamp(),
				HistoryEntryType.CREATED);
		this.searchIndex.index(generatedId);

		return versioned.getIdentifier().get();
	}

	private Identifier update(Entity updateEntity) {

		updateEntity.getIdentifier()
				.orElseThrow(() -> new IllegalArgumentException("Update failed, identifier missing"));

		long id = this.repository.resolve(updateEntity.getIdentifier().get());

		Optional<Entity> existsEntity = this.repository.get(id);
		existsEntity.orElseThrow(() -> new EntityNotFound(id));

		if (!this.accessControl.isEntityWritable(id)) {
			throw new AccessDenied("Not allowed to update entity " + updateEntity.getIdentifier());
		}

		Optional<EntityLock> lock = this.lockManager.getLock(id);
		lock.ifPresent(l -> {
			throw new EntityLocked(l);
		});

		ChangeSet changeSet = this.contentDiffer.diff(updateEntity, existsEntity.get());
		if (changeSet.isEmpty()) {
			return updateEntity.getIdentifier().get();
		}

		Entity versioned = this.versioningManager.createNewVersion(updateEntity);

		this.repository.store(versioned);
		this.historyManager.createEntry(id, versioned.getVersion().get().getTimestamp(),
				HistoryEntryType.UPDATED);
		this.searchIndex.index(id);

		return versioned.getIdentifier().get();
	}
}
