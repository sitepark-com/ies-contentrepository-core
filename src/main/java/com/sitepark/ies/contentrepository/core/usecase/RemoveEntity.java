package com.sitepark.ies.contentrepository.core.usecase;

import java.util.Optional;

import com.sitepark.ies.contentrepository.core.domain.entity.Entity;
import com.sitepark.ies.contentrepository.core.domain.entity.EntityLock;
import com.sitepark.ies.contentrepository.core.domain.entity.HistoryEntryType;
import com.sitepark.ies.contentrepository.core.domain.entity.RecycleBinItem;
import com.sitepark.ies.contentrepository.core.domain.exception.AccessDenied;
import com.sitepark.ies.contentrepository.core.domain.exception.EntityLocked;
import com.sitepark.ies.contentrepository.core.domain.exception.GroupNotEmpty;
import com.sitepark.ies.contentrepository.core.port.AccessControl;
import com.sitepark.ies.contentrepository.core.port.ContentRepository;
import com.sitepark.ies.contentrepository.core.port.EntityLockManager;
import com.sitepark.ies.contentrepository.core.port.HistoryManager;
import com.sitepark.ies.contentrepository.core.port.Publisher;
import com.sitepark.ies.contentrepository.core.port.RecycleBin;
import com.sitepark.ies.contentrepository.core.port.SearchIndex;

public final class RemoveEntity {

	private final ContentRepository repository;
	private final EntityLockManager lockManager;
	private final HistoryManager historyManager;
	private final AccessControl accessControl;
	private final RecycleBin recycleBin;
	private final SearchIndex searchIndex;
	private final Publisher publisher;

	protected RemoveEntity(ContentRepository repository, EntityLockManager lockManager,
			HistoryManager historyManager, AccessControl accessControl,
			RecycleBin recycleBin, SearchIndex searchIndex, Publisher publisher) {

		this.repository = repository;
		this.lockManager = lockManager;
		this.historyManager = historyManager;
		this.accessControl = accessControl;
		this.recycleBin = recycleBin;
		this.searchIndex = searchIndex;
		this.publisher = publisher;
	}

	public void remove(long id) {
		if (this.repository.isGroup(id)) {
			this.removeGroup(id);
		} else {
			this.removeEntity(id);
		}
	}

	public void removeGroup(long id) {

		if (!this.accessControl.isGroupRemoveable(id)) {
			throw new AccessDenied("Not allowed to remove group " + id);
		}

		if (!this.repository.isEmptyGroup(id)) {
			throw new GroupNotEmpty(id);
		}

		Optional<EntityLock> lock = this.lockManager.getLock(id);
		lock.ifPresent(l -> {
			throw new EntityLocked(l);
		});

		this.searchIndex.remove(id);

		this.repository.removeGroup(id);

		this.historyManager.createEntry(id, System.currentTimeMillis(), HistoryEntryType.REMOVED);

		RecycleBinItem recycleBinItem = RecycleBinItem.builder().build();
		this.recycleBin.add(recycleBinItem);

	}

	public void removeEntity(long id) {

		if (!this.accessControl.isEntityRemovable(id)) {
			throw new AccessDenied("Not allowed to remove entity " + id);
		}

		Optional<Entity> entity = this.repository.get(id);
		if (entity.isEmpty()) {
			return;
		}

		Optional<EntityLock> lock = this.lockManager.getLock(id);
		lock.ifPresent(l -> {
			throw new EntityLocked(l);
		});

		this.searchIndex.remove(id);

		this.publisher.depublish(id);

		this.repository.removeEntity(id);

		this.historyManager.createEntry(id, System.currentTimeMillis(), HistoryEntryType.REMOVED);

		RecycleBinItem recycleBinItem = RecycleBinItem.builder().build();
		this.recycleBin.add(recycleBinItem);
	}
}
