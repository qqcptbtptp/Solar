/*
 * Copyright (c) 2013 Nimbits Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.  See the License for the specific language governing permissions and limitations under the License.
 */

package com.nimbits.server.io;


import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.nimbits.client.common.Utils;
import com.nimbits.client.constants.Const;
import com.nimbits.client.enums.ServerSetting;
import com.nimbits.client.model.entity.Entity;
import com.nimbits.client.model.point.Point;
import com.nimbits.client.model.value.Value;
import com.nimbits.client.model.value.impl.ValueModel;
import com.nimbits.client.model.valueblobstore.ValueBlobStore;
import com.nimbits.client.model.valueblobstore.ValueBlobStoreFactory;
import com.nimbits.server.defrag.ValueDayHolder;
import com.nimbits.server.gson.deserializer.ValueDeserializer;
import com.nimbits.server.orm.store.ValueBlobStoreEntity;
import com.nimbits.server.transaction.settings.SettingsService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;
import javax.jdo.Query;
import javax.jdo.Transaction;
import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.logging.Logger;

@Repository
public class BlobStoreImpl implements BlobStore {
    private final Logger logger = Logger.getLogger(BlobStoreImpl.class.getName());

    private PersistenceManagerFactory persistenceManagerFactory;

    @Autowired
    private SettingsService settingsService;

    private final Gson gson = new GsonBuilder()
            .setDateFormat(Const.GSON_DATE_FORMAT)
            .registerTypeAdapter(Value.class, new ValueDeserializer())
            .excludeFieldsWithoutExposeAnnotation()
            .create();


    public BlobStoreImpl() {

    }

    public void setPersistenceManagerFactory(PersistenceManagerFactory persistenceManagerFactory) {
        this.persistenceManagerFactory = persistenceManagerFactory;
    }

    private boolean validateOwnership(Entity entity, ValueBlobStore e) {
        return e.getEntityUUID().equals("") || e.getEntityUUID().equals(entity.getUUID());
    }

    @Override
    public List<Value> getTopDataSeries(final Entity entity, final int maxValues, final Date endDate) {
        PersistenceManager pm = persistenceManagerFactory.getPersistenceManager();
        try {

            final List<Value> retObj = new ArrayList<Value>(maxValues);

            final Query q = pm.newQuery(ValueBlobStoreEntity.class);
            q.setFilter("minTimestamp <= t && entity == k");
            q.declareParameters("String k, Long t");
            q.setOrdering("minTimestamp desc");
            q.setRange(0, maxValues);

            final List<ValueBlobStoreEntity> result = (List<ValueBlobStoreEntity>) q.execute(entity.getKey(), endDate.getTime());

            for (final ValueBlobStoreEntity e : result) {
                if (validateOwnership(entity, e)) {
                    List<Value> values = readValuesFromFile(e.getBlobKey());
                    logger.info("reading values from blob " + values.size());
                    for (final Value vx : values) {
                        if (vx.getTimestamp().getTime() <= endDate.getTime()) {
                            retObj.add(vx);
                        }

                        if (retObj.size() >= maxValues) {
                            break;
                        }
                    }
                }
            }
            return retObj;
        } finally {
           pm.close();
        }
    }


    @Override
    public List<Value> getTopDataSeries(final Entity entity, final int maxValues) {
        PersistenceManager pm = persistenceManagerFactory.getPersistenceManager();
        try {

            final List<Value> retObj = new ArrayList<Value>(maxValues);

            final Query q = pm.newQuery(ValueBlobStoreEntity.class);
            q.setFilter("entity == k");
            q.declareParameters("String k");
            q.setOrdering("minTimestamp desc");
            q.setRange(0, 1000);

            final List<ValueBlobStoreEntity> result = (List<ValueBlobStoreEntity>) q.execute(entity.getKey());

            for (final ValueBlobStoreEntity e : result) {
                if (validateOwnership(entity, e)) {
                    List<Value> values = readValuesFromFile(e.getBlobKey());
                    logger.info("reading values from blob " + values.size());
                    for (final Value vx : values) {
                        retObj.add(vx);

                        if (retObj.size() >= maxValues) {
                            break;
                        }
                    }
                }
            }
            return ImmutableList.copyOf(retObj);
        } finally {
           pm.close();
        }
    }

    @Override
    public List<Value> getDataSegment(final Entity entity, final Range<Date> timespan) {
        PersistenceManager pm = persistenceManagerFactory.getPersistenceManager();
        try {
            final List<Value> retObj = new ArrayList<Value>();
            final Query q = pm.newQuery(ValueBlobStoreEntity.class);
            q.setFilter("entity == k && minTimestamp <= et && maxTimestamp >= st ");
            q.declareParameters("String k, Long et, Long st");
            q.setOrdering("minTimestamp desc");

            final Iterable<ValueBlobStore> result = (Iterable<ValueBlobStore>) q.execute(entity.getKey(), timespan.upperEndpoint().getTime(), timespan.lowerEndpoint().getTime());
            for (final ValueBlobStore e : result) {    //todo break out of loop when range is met
                if (validateOwnership(entity, e)) {
                    List<Value> values = readValuesFromFile(e.getBlobKey());
                    for (final Value vx : values) {
                        if (timespan.contains(vx.getTimestamp())) {
                            retObj.add(vx);

                        }
                    }
                }
            }
            return retObj;
        } finally {
            pm.close();
        }
    }


    @Override
    public List<ValueBlobStore> getAllStores(final Entity entity) {
        PersistenceManager pm = persistenceManagerFactory.getPersistenceManager();
        try {

            final Query q = pm.newQuery(ValueBlobStoreEntity.class);
            q.setFilter("entity == k");
            q.declareParameters("String k");
            q.setOrdering("timestamp descending");

            final Collection<ValueBlobStore> result = (Collection<ValueBlobStore>) q.execute(entity.getKey());
            List<ValueBlobStore> checked = new ArrayList<>(result.size());
            {
                for (ValueBlobStore e : result) {
                    if (validateOwnership(entity, e)) {
                        checked.add(e);
                    }
                }
            }
            return ValueBlobStoreFactory.createValueBlobStores(checked);
        } finally {
            pm.close();
        }
    }

    @Override
    public List<ValueBlobStore> getLegacy() {
        return Collections.emptyList();
    }

    @Override
    public void deleteStores(final Entity entity, final Date timestamp) {
        PersistenceManager pm = persistenceManagerFactory.getPersistenceManager();


        final Query q = pm.newQuery(ValueBlobStoreEntity.class);
        q.setFilter("timestamp == t && entity == k");
        q.declareParameters("String k, Long t");

        //  Transaction tx = pm.currentTransaction();
        try {

            //   tx.begin();
            final List<ValueBlobStoreEntity> result = (List<ValueBlobStoreEntity>) q.execute(entity.getKey(), timestamp.getTime());
             pm.deletePersistentAll(result);

            //  tx.commit();

        }
        catch (Exception ex) {
              ex.printStackTrace();
            //  tx.rollback();


        } finally {
            pm.close();
        }
    }

    @Override
    public List<Value> consolidateDate(final Entity entity, final Date timestamp) {
        PersistenceManager pm = persistenceManagerFactory.getPersistenceManager();


        final Query q = pm.newQuery(ValueBlobStoreEntity.class);
        q.setFilter("timestamp == t && entity == k");
        q.declareParameters("String k, Long t");
        q.setOrdering("timestamp desc");



        try {

            final List<ValueBlobStore> result = (List<ValueBlobStore>) q.execute(entity.getKey(), timestamp.getTime());

            final List<Value> values = new ArrayList<>(Const.CONST_DEFAULT_LIST_SIZE);
            for (final ValueBlobStore store : result) {
                if (validateOwnership(entity, store)) {
                    values.addAll(readValuesFromFile(store));
                }
            }

            deleteGcs(result);


            return values;
        }
        catch (Exception ex) {
             return Collections.emptyList();  //TODO if anything goes wrong with this process you'll lose an entire day's worth of data

        } finally {
            pm.close();
        }


    }

    @Override
    public int deleteExpiredData(final Entity entity ) {
        PersistenceManager pm = persistenceManagerFactory.getPersistenceManager();


        int exp = ((Point) entity).getExpire();
        int deleted = 0;
        if (exp > 0) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DATE, exp * -1);
            try {
                final Query q = pm.newQuery(ValueBlobStoreEntity.class);
                q.setFilter("entity == k && maxTimestamp <= et");
                q.declareParameters("String k, Long et");
                q.setRange(0,  1000);


                final List<ValueBlobStore> result = (List<ValueBlobStore>) q.execute(
                        entity.getKey(), c.getTime().getTime());
                deleted = result.size();

                delete(result);
                pm.deletePersistentAll(result);

            } finally {
                pm.close();
            }

        }
        return deleted;
    }

    private String readFile(final String fn) throws IOException {
        String folder = getFolder();


        try (BufferedReader br = new BufferedReader(new FileReader(folder + fn))) {

            StringBuilder sb = new StringBuilder();
            String line = br.readLine();

            while (line != null) {
                sb.append(line);
                sb.append('\n');
                line = br.readLine();
            }
            return sb.toString();
        }

    }

    private String getFolder() {
        String failover = "/tmp/";
        if (settingsService == null) {
            return failover;
        } else {

            String folder = settingsService.getSetting(ServerSetting.storeDirectory);
            if (folder == null) {
                folder = failover;
            }
            if (!folder.endsWith("/")) {
                folder += "/";
            }
            return folder;
        }
    }


    @Override
    public List<Value> readValuesFromFile(ValueBlobStore store) {
        return readValuesFromFile(store.getBlobKey());
    }




    private List<Value> readValuesFromFile(final String key) {

        final Type valueListType = new TypeToken<List<ValueModel>>() {
        }.getType();
        List<Value> models;


        try {

            String segment = readFile(key);
            if (!Utils.isEmptyString(segment)) {
                models = gson.fromJson(segment, valueListType);
                if (models != null) {
                    Collections.sort(models);
                } else {
                    models = Collections.emptyList();
                }
            } else {
                models = Collections.emptyList();
            }
            return models;
        } catch (IllegalArgumentException ex) {
            return Collections.emptyList();
        } catch (IOException e) {
            return Collections.emptyList();
        }

    }

    @Override
    public void deleteGcs(List<ValueBlobStore> result) {
        for (ValueBlobStore store : result) {
            final String blobKey = store.getBlobKey();
            File file = new File(getFolder() + blobKey);
            file.delete();

        }
    }

    @Override
    public void deleteBlobStore(final String key) {
        File file = new File(getFolder() + key);
        file.delete();

    }

    @Override
    public List<ValueBlobStore> createBlobStoreEntity(final Entity entity, final ValueDayHolder holder) throws IOException {
        PersistenceManager pm = persistenceManagerFactory.getPersistenceManager();
        PrintWriter out = null;
        try {
            logger.info("Creating Blobstore for " + holder.getValues().size());
            final String json = gson.toJson(holder.getValues());
            String fn = entity.getName().getValue() + "_" + UUID.randomUUID().toString();
            out = new PrintWriter(getFolder() + fn);
            out.println(json);
            out.close();


            Range<Date> range = holder.getTimeRange();
            final Date mostRecentTimeForDay = range.upperEndpoint();
            final Date earliestForDay = range.lowerEndpoint();
            final ValueBlobStoreEntity currentStoreEntity = new
                    ValueBlobStoreEntity(entity.getKey(),
                    holder.getStartOfDay(),
                    mostRecentTimeForDay,
                    earliestForDay,
                    fn,
                    json.length(),
                    BlobStore.storageVersion,
                    entity.getUUID()

            );

            currentStoreEntity.validate();

            pm.makePersistent(currentStoreEntity);

            pm.flush();

            return ValueBlobStoreFactory.createValueBlobStore(currentStoreEntity);


        } finally {
            if (out != null) {
                out.close();
            }
            pm.close();
        }

    }

    @Override
    public List<ValueBlobStore> mergeTimespan(final Entity entity, final Range<Date> timespan) {
        PersistenceManager pm = persistenceManagerFactory.getPersistenceManager();

        PrintWriter out = null;
        try {
            String fn = UUID.randomUUID().toString();

            out = new PrintWriter(fn);

            final Query q = pm.newQuery(ValueBlobStoreEntity.class);

            q.setFilter("entity == k && minTimestamp <= et && minTimestamp >= st ");
            q.declareParameters("String k, Long et, Long st");
            q.setOrdering("minTimestamp desc");

            final List<ValueBlobStore> result = (List<ValueBlobStore>) q.execute(
                    entity.getKey(),
                    timespan.upperEndpoint().getTime(),
                    timespan.lowerEndpoint().getTime());


            Collection<Value> combined = new ArrayList<Value>();
            Date timestamp = null;
            for (ValueBlobStore store : result) {
                if (timestamp == null || timestamp.getTime() > store.getTimestamp().getTime()) {
                    timestamp = store.getTimestamp();

                }
                List<Value> read = readValuesFromFile((store.getBlobKey()));
                combined.addAll(read);
                deleteBlobStore(store.getBlobKey());

            }


            pm.deletePersistentAll(result);


            long max = 0;
            long min = 0;
            for (Value v : combined) {
                if (v.getTimestamp().getTime() > max) {
                    max = v.getTimestamp().getTime();
                }
                if (v.getTimestamp().getTime() < min || min == 0) {
                    min = v.getTimestamp().getTime();
                }
            }

            String json = gson.toJson(combined);
            // byte[] compressed = CompressionImpl.compressBytes(json);
            out.print(json);
            out.close();


            ValueBlobStore currentStoreEntity = new ValueBlobStoreEntity(
                    entity.getKey(),
                    timestamp,
                    new Date(max),
                    new Date(min),
                    fn,
                    json.length(), BlobStore.storageVersion, entity.getUUID());

            currentStoreEntity.validate();
            pm.makePersistent(currentStoreEntity);
            pm.flush();
            return ValueBlobStoreFactory.createValueBlobStore(currentStoreEntity);
        } catch (IOException ex) {
            return Collections.emptyList();

        } finally {
            if (out != null) {
                out.close();
            }
            pm.close();
        }

    }

    @Override
    public void delete(List<ValueBlobStore> result) {
        for (ValueBlobStore store : result) {
            deleteBlobStore(store.getBlobKey());
        }
    }

    @Override
    public void deleteBlobStoreEntity(List<ValueBlobStore> s) {
        PersistenceManager pm = persistenceManagerFactory.getPersistenceManager();

        try {
            Transaction tx =  pm.currentTransaction();
            tx.begin();
            ValueBlobStore st = s.get(0);
            final ValueBlobStore result =   pm.getObjectById(ValueBlobStoreEntity.class, st.getId());
            pm.deletePersistent(result);

            tx.commit();



        } finally {
           pm.close();
        }
    }

    @Override
    public List<Value> upgradeStore(Entity entity, ValueBlobStore v) {
        return null;
    }

    @Override
    public List<Value> getLegacyStores(Point point) {
        return Collections.emptyList();
    }

    @Override
    public int doClean() {
return 0;
    }


}