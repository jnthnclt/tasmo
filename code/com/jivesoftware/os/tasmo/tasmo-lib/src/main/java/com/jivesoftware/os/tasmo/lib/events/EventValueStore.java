/*
 * $Revision$
 * $Date$
 *
 * Copyright (C) 1999-$year$ Jive Software. All rights reserved.
 *
 * This software is the proprietary information of Jive Software. Use is subject to license terms.
 */
package com.jivesoftware.os.tasmo.lib.events;

import com.google.common.collect.Lists;
import com.jivesoftware.os.jive.utils.logger.MetricLogger;
import com.jivesoftware.os.jive.utils.logger.MetricLoggerFactory;
import com.jivesoftware.os.jive.utils.row.column.value.store.api.ColumnValueAndTimestamp;
import com.jivesoftware.os.jive.utils.row.column.value.store.api.RowColumnValueStore;
import com.jivesoftware.os.jive.utils.row.column.value.store.api.timestamper.ConstantTimestamper;
import com.jivesoftware.os.tasmo.id.ObjectId;
import com.jivesoftware.os.tasmo.id.TenantIdAndCentricId;
import com.jivesoftware.os.tasmo.model.process.OpaqueFieldValue;
import java.util.List;

/**
 *
 */
public class EventValueStore {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();
    private final RowColumnValueStore<TenantIdAndCentricId, ObjectId, String, OpaqueFieldValue, RuntimeException> eventValueStore;

    public EventValueStore(RowColumnValueStore<TenantIdAndCentricId, ObjectId, String, OpaqueFieldValue, RuntimeException> classFieldValueStore) {
        this.eventValueStore = classFieldValueStore;
    }

    public ColumnValueAndTimestamp<String, OpaqueFieldValue, Long>[] get(
            TenantIdAndCentricId tenantIdAndCentricId, ObjectId objectId, String[] fieldNames) {
            return eventValueStore.multiGetEntries(tenantIdAndCentricId, objectId, fieldNames, null, null);
    }

    public void removeObjectId(TenantIdAndCentricId tenantIdAndCentricId,
            long removeAtTimestamp,
            ObjectId objectId) {
        eventValueStore.removeRow(tenantIdAndCentricId, objectId, new ConstantTimestamper(removeAtTimestamp));
    }

    public Transaction begin(TenantIdAndCentricId tenantIdAndCentricId,
            long addAtTimestamp,
            long removeAtTimestamp,
            ObjectId objectId) {
        return new Transaction(tenantIdAndCentricId, addAtTimestamp, removeAtTimestamp, objectId);
    }

    public void commit(Transaction transaction) {

        ObjectId objectInstanceId = transaction.objectInstanceId;
        if (!transaction.addedFieldNames.isEmpty()) {
            String[] takeAddedFieldNames = transaction.takeAddedFieldNames();
            OpaqueFieldValue[] takeAddedValues = transaction.takeAddedValues();

            eventValueStore.multiAdd(
                    transaction.tenantIdAndCentricId,
                    objectInstanceId,
                    takeAddedFieldNames,
                    takeAddedValues,
                    null, new ConstantTimestamper(transaction.addAtTimestamp));

            if (LOG.isTraceEnabled()) {
                for (int i = 0; i < takeAddedFieldNames.length; i++) {
                    LOG.trace(" |--> Set: Tenant={} Instance={} Field={} Value={} Time={}", new Object[]{
                        transaction.tenantIdAndCentricId, objectInstanceId, takeAddedFieldNames[i], takeAddedValues[i], transaction.addAtTimestamp});
                }
            }
        }
        if (!transaction.removedFieldNames.isEmpty()) {
            String[] takeRemovedFieldNames = transaction.takeRemovedFieldNames();

            eventValueStore.multiRemove(
                    transaction.tenantIdAndCentricId,
                    objectInstanceId,
                    takeRemovedFieldNames,
                    new ConstantTimestamper(transaction.removeAtTimestamp));
            if (LOG.isTraceEnabled()) {
                for (String takeRemovedFieldName : takeRemovedFieldNames) {
                    LOG.trace(" |--> Remove: Tenant={} Instance={} Field={} Time={}",
                            new Object[]{transaction.tenantIdAndCentricId, objectInstanceId, takeRemovedFieldName});
                }
            }

        }

    }

    final public static class Transaction {

        private final TenantIdAndCentricId tenantIdAndCentricId;
        private final long addAtTimestamp;
        private final long removeAtTimestamp;
        private final ObjectId objectInstanceId;
        private List<String> addedFieldNames;
        private List<OpaqueFieldValue> addedValues;
        private List<String> removedFieldNames;
        private final Thread constructingThread;

        private Transaction(TenantIdAndCentricId tenantIdAndCentricId,
                long addAtTimestamp,
                long removeAtTimestamp,
                ObjectId objectId) {
            this.tenantIdAndCentricId = tenantIdAndCentricId;
            this.addAtTimestamp = addAtTimestamp;
            this.removeAtTimestamp = removeAtTimestamp;
            this.objectInstanceId = objectId;
            this.addedFieldNames = Lists.newLinkedList();
            this.addedValues = Lists.newLinkedList();
            this.removedFieldNames = Lists.newLinkedList();
            this.constructingThread = Thread.currentThread();
        }

        public int size() {
            return addedFieldNames.size() + removedFieldNames.size();
        }

        public void set(String fieldName, OpaqueFieldValue value) {
            if (!Thread.currentThread().equals(constructingThread)) {
                throw new IllegalStateException("calling thread must be the same as creating thread.");
            }
            addedFieldNames.add(fieldName);
            addedValues.add(value);
        }

        public String[] takeAddedFieldNames() {
            try {
                return addedFieldNames.toArray(new String[addedFieldNames.size()]);
            } finally {
                addedFieldNames = Lists.newArrayList();
            }
        }

        public OpaqueFieldValue[] takeAddedValues() {
            try {
                return addedValues.toArray(new OpaqueFieldValue[addedValues.size()]);
            } finally {
                addedValues = Lists.newArrayList();
            }
        }

        public void remove(String fieldName) {
            if (!Thread.currentThread().equals(constructingThread)) {
                throw new IllegalStateException("calling thread must be the same as creating thread.");
            }
            removedFieldNames.add(fieldName);
        }

        public String[] takeRemovedFieldNames() {
            try {
                return removedFieldNames.toArray(new String[removedFieldNames.size()]);
            } finally {
                removedFieldNames = Lists.newArrayList();
            }
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 23 * hash + (this.tenantIdAndCentricId != null ? this.tenantIdAndCentricId.hashCode() : 0);
            hash = 23 * hash + (this.objectInstanceId != null ? this.objectInstanceId.hashCode() : 0);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final Transaction other = (Transaction) obj;
            if ((this.tenantIdAndCentricId == null) ? (other.tenantIdAndCentricId != null) : !this.tenantIdAndCentricId.equals(other.tenantIdAndCentricId)) {
                return false;
            }
            if (this.objectInstanceId != other.objectInstanceId && (this.objectInstanceId == null || !this.objectInstanceId.equals(other.objectInstanceId))) {
                return false;
            }
            return true;
        }
    }
}