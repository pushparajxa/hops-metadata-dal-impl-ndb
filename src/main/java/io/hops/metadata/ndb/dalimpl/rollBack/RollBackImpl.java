/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package io.hops.metadata.ndb.dalimpl.rollBack;

import io.hops.exception.StorageException;
import io.hops.metadata.hdfs.TablesDef;
import io.hops.metadata.hdfs.entity.INodeAttributes;
import io.hops.metadata.hdfs.snapshots.SnapShotConstants;
import io.hops.metadata.ndb.ClusterjConnector;
import io.hops.metadata.ndb.dalimpl.hdfs.BlockInfoClusterj.BlockInfoDTO;
import io.hops.metadata.ndb.dalimpl.hdfs.INodeAttributesClusterj.INodeAttributesDTO;
import io.hops.metadata.ndb.dalimpl.hdfs.INodeClusterj.InodeDTO;
import io.hops.metadata.ndb.mysqlserver.MysqlServerConnector;
import io.hops.metadata.ndb.wrapper.*;
import io.hops.metadata.rollBack.dal.RollBackAccess;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import io.hops.metadata.hdfs.TablesDef.*;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
/**
 *
 * @author pushparaj
 */
public class RollBackImpl implements RollBackAccess {

    private static final Log LOG = LogFactory.getLog(RollBackImpl.class);
    private static int BUFFER_SIZE = 50000;
    private int rootId = 2;

    @Override
    public boolean processInodesPhase1() throws IOException {
        ExecutorService pool = Executors.newCachedThreadPool();
        int intervalStart;
        int maxId = 0;
        int minId;
        //Delete all rows with status=2 or status=3

        try {
            maxId = execMySqlQuery("select max(id) from hdfs_inodes where status=2 or status=3");
            minId = execMySqlQuery("select min(id) from hdfs_inodes where status=2 or status=3");

        } catch (StorageException ex) {
            // Logger.getLogger(RollBackImpl.class.getName()).log(Level.SEVERE, null, ex);
            throw new StorageException(ex);
        }

        intervalStart = minId;
        InodesPhase1Callable dr;
        while (intervalStart <= maxId) {
            dr = new InodesPhase1Callable(intervalStart, intervalStart + BUFFER_SIZE);
            intervalStart = intervalStart + BUFFER_SIZE;
            pool.submit(dr);
        }

        //Wait for completion of above task.
        shutDownPool(pool);

        //Confirm that the task has done.
        int count = 1;
        try {
            count = execMySqlQuery("select count(*) from hdfs_inodes where status=2 or status=3");

        } catch (StorageException ex) {
            // Logger.getLogger(RollBackImpl.class.getName()).log(Level.SEVERE, null, ex);
            throw new StorageException(ex);
        }

        return count == 0;
    }

    @Override
    public boolean processInodesPhase2() throws IOException {
        ExecutorService pool = Executors.newCachedThreadPool();
        int intervalStart = 0;
        int minId = 0;
        //Update all rows with id>0 and isDeleted=1 to isDeleted=0

        try {
            minId = execMySqlQuery("select min(id) from hdfs_inodes where id <0 and isDeleted=1");
        } catch (StorageException ex) {
            //LOG.error(ex, ex);
            throw new StorageException(ex);
        }


        intervalStart = 0;
        InodesPhase2Callable mr;
        while (intervalStart > minId) {
            mr = new InodesPhase2Callable(intervalStart, intervalStart - BUFFER_SIZE);
            intervalStart = intervalStart - BUFFER_SIZE;
            pool.submit(mr);
        }

        //Wait for completion of above task.
        shutDownPool(pool);

        //Confirm that the task has done.
        int count = 1;
        try {
            count = execMySqlQuery("select count(*) from hdfs_inodes where  id <0 and isDeleted=1");

        } catch (StorageException ex) {
            //Logger.getLogger(RollBackImpl.class.getName()).log(Level.SEVERE, null, ex);
            throw new StorageException(ex);
        }

        return count == 0;
    }

    static int execMySqlQuery(String query) throws StorageException {
        MysqlServerConnector connector = MysqlServerConnector.getInstance();

        try {
            Connection conn = connector.obtainSession();
            PreparedStatement s = conn.prepareStatement(query);
            ResultSet result = s.executeQuery();
            if (result.next()) {
                return result.getInt(1);
            } else {
                throw new StorageException(
                        String.format("Count result set is empty. Query: %s", query));
            }
        } catch (SQLException ex) {
            throw new StorageException(ex);
        } finally {
            connector.closeSession();
        }


    }

    static void shutDownPool(ExecutorService pool) {
        //Wait for completion of above task.
        pool.shutdown();
        try {
            //pool.awaitTermination(10, TimeUnit.MINUTES);
            while (!pool.isTerminated()) {
                Thread.sleep(500);
            }
            ;
        } catch (InterruptedException ex) {
            LOG.error(ex, ex);
        }

    }

    @Override
    public boolean processInodesPhase3() throws IOException {
        //Insert new row for each backup row with [new row's id]=-[back-up row's id] and [new row's parentid]=-[back-up row's parentid]

        ExecutorService pool = Executors.newCachedThreadPool();
        int intervalStart = 0;
        int minId = 0;

        try {
            //Get total number of backup records.
            minId = execMySqlQuery("select min(id) from hdfs_inodes where id <0 ");
            LOG.debug("The minimum id id " + minId);
            //System.out.println(backUpRowsCnt);
        } catch (StorageException ex) {
            LOG.error(ex, ex);
        }

        InodesPhase3Callable ir;

        Lock lock = new Lock();

        while (intervalStart >= minId) {
            ir = new InodesPhase3Callable(intervalStart, intervalStart - BUFFER_SIZE, lock);
            //doInsert(intervalStart, intervalStart-BUFFER_SIZE);
            intervalStart = intervalStart - BUFFER_SIZE;

            pool.submit(ir);
        }

        //Wait for completion of above task.
        shutDownPool(pool);

        //Confirm that the task has done.
        int count = 1;
        try {
            count = execMySqlQuery("select count(*) from hdfs_inodes where id<0");

        } catch (StorageException ex) {
            // Logger.getLogger(RollBackImpl.class.getName()).log(Level.SEVERE, null, ex);
            throw new StorageException(ex);
        }

        return count == 0;
    }

    @Override
    public boolean processInodeAttributesPhase1() throws IOException {
        ExecutorService pool = Executors.newCachedThreadPool();
        int intervalStart;
        int maxId = 0;
        //Delete all rows with status=2 or status=3

        try {
            maxId = execMySqlQuery("select max(inodeId) from hdfs_inode_attributes where status=2 or status=3");

        } catch (StorageException ex) {
            //  Logger.getLogger(RollBackImpl.class.getName()).log(Level.SEVERE, null, ex);
            throw new StorageException(ex);
        }

        intervalStart = 0;
        InodeAttributesPhase1Callable dr;
        while (intervalStart <= maxId) {
            dr = new InodeAttributesPhase1Callable(intervalStart, intervalStart + BUFFER_SIZE);
            intervalStart = intervalStart + BUFFER_SIZE;
            pool.submit(dr);
        }

        //Wait for completion of above task.
        shutDownPool(pool);

        //Confirm that the task has done.
        int count = 1;
        try {
            count = execMySqlQuery("select count(*) from hdfs_inode_attributes where status=2 or status=3");

        } catch (StorageException ex) {
            // Logger.getLogger(RollBackImpl.class.getName()).log(Level.SEVERE, null, ex);
            throw new StorageException(ex);
        }

        return count == 0;
    }

    @Override
    public boolean processInodeAttributesPhase2() throws IOException {
        //Insert new row for each backup row with [new row's id]=-[back-up row's id] and [new row's parentid]=-[back-up row's parentid]

        ExecutorService pool = Executors.newCachedThreadPool();
        int intervalStart = 0;
        int minId = 0;

        try {
            //Get total number of backup records.
            minId = execMySqlQuery("select min(inodeId) from hdfs_inode_attributes  where inodeId <0 ");
            //System.out.println(backUpRowsCnt);
        } catch (StorageException ex) {
            LOG.error(ex, ex);
        }

        InodeAttributesPhase2Callable ir;

        Lock lock = new Lock();

        while (intervalStart >= minId) {
            ir = new InodeAttributesPhase2Callable(intervalStart, intervalStart - BUFFER_SIZE, lock);
            //doInsert(intervalStart, intervalStart-BUFFER_SIZE);
            intervalStart = intervalStart - BUFFER_SIZE;

            pool.submit(ir);
        }

        //Wait for completion of above task.
        shutDownPool(pool);

        //Confirm that the task has done.
        int count = 1;
        try {
            count = execMySqlQuery("select count(*) from hdfs_inode_attributes where inodeId<0");

        } catch (StorageException ex) {
            //Logger.getLogger(RollBackImpl.class.getName()).log(Level.SEVERE, null, ex);
            throw new StorageException(ex);
        }

        return count == 0;

    }

    @Override
    public boolean processBlocksPhase1() throws IOException {
        ExecutorService pool = Executors.newCachedThreadPool();
        int intervalStart;
        int maxId = 0;
        //Delete all rows with status=2 or status=3

        try {
            maxId = execMySqlQuery("select max(block_id) from hdfs_block_infos where status=2 or status=3");

        } catch (StorageException ex) {
            //Logger.getLogger(RollBackImpl.class.getName()).log(Level.SEVERE, null, ex);
            throw new StorageException(ex);
        }

        intervalStart = 0;
        BlocksPhase1Callable dr;
        while (intervalStart <= maxId) {
            dr = new BlocksPhase1Callable(intervalStart, intervalStart + BUFFER_SIZE);
            intervalStart = intervalStart + BUFFER_SIZE;
            pool.submit(dr);
        }

        //Wait for completion of above task.
        shutDownPool(pool);

        //Confirm that the task has done.
        int count = 1;
        try {
            count = execMySqlQuery("select count(*) from hdfs_block_infos where status=2 or status=3");

        } catch (StorageException ex) {
            //Logger.getLogger(RollBackImpl.class.getName()).log(Level.SEVERE, null, ex);
            throw new StorageException(ex);
        }

        return count == 0;
    }

    @Override
    public boolean processBlocksPhase2() throws IOException {
        //Insert new row for each backup row with [new row's id]=-[back-up row's id] and [new row's parentid]=-[back-up row's parentid]
        ExecutorService pool = Executors.newCachedThreadPool();
        int intervalStart = 0;
        int minId = 0;

        try {
            //Get total number of backup records.
            minId = execMySqlQuery("select min(block_id) from hdfs_block_infos   where block_id <0 ");
            //System.out.println(backUpRowsCnt);
        } catch (StorageException ex) {
            LOG.error(ex, ex);
        }

        BlocksPhase2Callable ir;
        Lock lock = new Lock();

        while (intervalStart >= minId) {
            ir = new BlocksPhase2Callable(intervalStart, intervalStart - BUFFER_SIZE, lock);
            //doInsert(intervalStart, intervalStart-BUFFER_SIZE);
            intervalStart = intervalStart - BUFFER_SIZE;
            pool.submit(ir);
        }

        //Wait for completion of above task.
        shutDownPool(pool);

        //Confirm that the task has done.
        int count = 1;
        try {
            count = execMySqlQuery("select count(*) from hdfs_block_infos  where block_id<0");

        } catch (StorageException ex) {
            //Logger.getLogger(RollBackImpl.class.getName()).log(Level.SEVERE, null, ex);
            throw new StorageException(ex);
        }

        return count == 0;
    }

    @Override
    public boolean unSetSubTreeLockedOnRoot() throws IOException {
        HopsSession session = ClusterjConnector.getInstance().obtainSession();
        int maxId;

        maxId = execMySqlQuery("select max(id) from hdfs_inodes where id >0");

        InodeDTO oldRoot = session.find(InodeDTO.class, new Integer(maxId));

        InodeDTO newRoot = session.newInstance(InodeDTO.class);

        newRoot.setId(rootId);
        newRoot.setName(oldRoot.getName());
        newRoot.setParentId(-oldRoot.getParentId());
        newRoot.setQuotaEnabled(oldRoot.getQuotaEnabled());
        newRoot.setModificationTime(oldRoot.getModificationTime());
        newRoot.setATime(oldRoot.getATime());
        newRoot.setPermission(oldRoot.getPermission());
        newRoot.setUnderConstruction(oldRoot.getUnderConstruction());
        newRoot.setClientName(oldRoot.getClientName());
        newRoot.setClientMachine(oldRoot.getClientMachine());
        newRoot.setClientNode(oldRoot.getClientNode());
        newRoot.setGenerationStamp(oldRoot.getGenerationStamp());
        newRoot.setHeader(oldRoot.getHeader());
        newRoot.setSymlink(oldRoot.getSymlink());
        newRoot.setSubtreeLocked((byte) 0);//UnsetSubTreeLockedToTree.
        newRoot.setSubtreeLockOwner(oldRoot.getSubtreeLockOwner());
        newRoot.setMetaEnabled(oldRoot.getMetaEnabled());
        newRoot.setSize(oldRoot.getSize());
        newRoot.setIsDeleted(oldRoot.getIsDeleted());
        newRoot.setStatus(oldRoot.getStatus());

        session.persist(newRoot);//Persist the new root;
        session.deletePersistent(InodeDTO.class, new Integer(maxId));//delete the old Root row.

        session.flush();
        session.close();

        return true;
    }

    @Override
    public  boolean waitForSubTreeOperations() throws IOException {
        String query = "select count(*) from hdfs_on_going_sub_tree_ops";
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 60 * 1000) {
            int onGoingSubTreeOpsCount = execMySqlQuery(query);
            if (onGoingSubTreeOpsCount == 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean waitForQuotaUpdates() throws IOException {
        String query = "select count(*) from hdfs_quota_update";
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 60 * 1000) {
            int quotaUpdatesYetToBeApplied = execMySqlQuery(query);
            if (quotaUpdatesYetToBeApplied == 0) {
                return true;
            }
        }
        return false;
    }

    private static class BlocksPhase1Callable implements Callable {

        long startId, endId;

        BlocksPhase1Callable(long startId, long endId) {
            this.startId = startId;
            this.endId = endId;
        }

        @Override
        public Boolean call() throws StorageException {

            HopsSession session = ClusterjConnector.getInstance().obtainSession();

            HopsQueryBuilder qb = session.getQueryBuilder();
            //Delete inodes with status=2 or status=3
            HopsQueryDomainType<BlockInfoDTO> deleteBlockInfos = qb.createQueryDefinition(BlockInfoDTO.class);
            HopsPredicate pred1 = deleteBlockInfos.get(BlockInfoTableDef.STATUS).equal(deleteBlockInfos.param("statusParam2"));
            HopsPredicate pred2 = deleteBlockInfos.get(BlockInfoTableDef.STATUS).equal(deleteBlockInfos.param("statusParam3"));
            HopsPredicate pred3 = deleteBlockInfos.get(BlockInfoTableDef.BLOCK_ID).greaterEqual(deleteBlockInfos.param("startId"));
            HopsPredicate pred4 = deleteBlockInfos.get(BlockInfoTableDef.BLOCK_ID).lessEqual(deleteBlockInfos.param("endId"));
            HopsPredicate pred5 = pred1.or(pred2);
            deleteBlockInfos.where(pred5.and(pred3).and(pred4));
            HopsQuery<BlockInfoDTO> query = session.createQuery(deleteBlockInfos);
            query.setParameter("statusParam2", SnapShotConstants.Modified);
            query.setParameter("statusParam3", SnapShotConstants.New);
            query.setParameter("startId", startId);
            query.setParameter("endId", endId);


            query.deletePersistentAll();

            // tx.commit();
            session.flush();
            session.close();
            return true;
        }
    }

    private static class BlocksPhase2Callable implements Callable {

        long startId, endId;
        final Lock lock;

        BlocksPhase2Callable(long startId, long endId, Lock lock) {
            this.startId = startId;
            this.endId = endId;
            this.lock = lock;
        }

        @Override
        public Boolean call() throws StorageException, IOException {
            try {

                HopsSession session = ClusterjConnector.getInstance().obtainSession();
//            Transaction tx = session.currentTransaction();
//            tx.begin();
                //System.err.println(Thread.currentThread().getId() + ": Started. StartId=" + startId + ", endId=" + endId);

                HopsQueryBuilder qb = session.getQueryBuilder();
                HopsQueryDomainType<BlockInfoDTO> updateBlockNodes = qb.createQueryDefinition(BlockInfoDTO.class);
                HopsPredicate pred3 = updateBlockNodes.get(BlockInfoTableDef.BLOCK_ID).lessEqual(updateBlockNodes.param("startId"));
                HopsPredicate pred4 = updateBlockNodes.get(BlockInfoTableDef.BLOCK_ID).greaterEqual(updateBlockNodes.param("endId"));

                updateBlockNodes.where(pred3.and(pred4));

                HopsQuery<BlockInfoDTO> UpdateQuery = session.createQuery(updateBlockNodes);
                UpdateQuery.setParameter("startId", startId);
                UpdateQuery.setParameter("endId", endId);


                List<BlockInfoDTO> results = UpdateQuery.getResultList();
                //System.out.println(Thread.currentThread().getId() + ": Result Size= " + results.size());
                List<BlockInfoDTO> newResults = new ArrayList<BlockInfoDTO>(results.size());

                BlockInfoDTO newBlockInfo;

                for (BlockInfoDTO row : results) {
                    newBlockInfo = session.newInstance(BlockInfoDTO.class);

                    newBlockInfo.setBlockId(-row.getBlockId());
                    newBlockInfo.setNumBytes(row.getNumBytes());
                    newBlockInfo.setGenerationStamp(row.getGenerationStamp());
                    newBlockInfo.setINodeId(-row.getINodeId());
                    newBlockInfo.setTimestamp(row.getTimestamp());
                    newBlockInfo.setBlockIndex(row.getBlockIndex());
                    newBlockInfo.setBlockUCState(row.getBlockUCState());
                    newBlockInfo.setPrimaryNodeIndex(row.getPrimaryNodeIndex());
                    newBlockInfo.setBlockRecoveryId(row.getBlockRecoveryId());
                    newBlockInfo.setStatus(row.getStatus());

                    newResults.add(newBlockInfo);
                }

                synchronized (lock) {
                    session.makePersistentAll(newResults);
                }
                System.gc();
                // System.out.println(Thread.currentThread().getId() + ": Acquired Lock. rows to write "+newResults.size());
                session = ClusterjConnector.getInstance().obtainSession();


                HopsQueryBuilder qbd = session.getQueryBuilder();
                HopsQueryDomainType<BlockInfoDTO> deleteInodesWithNegativeId = qbd.createQueryDefinition(BlockInfoDTO.class);
                HopsPredicate pred3d = deleteInodesWithNegativeId.get(BlockInfoTableDef.BLOCK_ID).lessEqual(deleteInodesWithNegativeId.param("startId"));
                HopsPredicate pred4d = deleteInodesWithNegativeId.get(BlockInfoTableDef.BLOCK_ID).greaterEqual(deleteInodesWithNegativeId.param("endId"));

                deleteInodesWithNegativeId.where(pred3d.and(pred4d));
                HopsQuery<BlockInfoDTO> queryToDeleteNegative = session.createQuery(deleteInodesWithNegativeId);
                queryToDeleteNegative.setParameter("startId", startId);
                queryToDeleteNegative.setParameter("endId", endId);
                synchronized (lock) {
                    queryToDeleteNegative.deletePersistentAll();
                }
                session.flush();
                session.close();
            } catch (Throwable e) {
                throw new IOException(e);
            }
            return true;

        }
    }

    private static class InodeAttributesPhase1Callable implements Callable {

        int startId, endId;

        InodeAttributesPhase1Callable(int startId, int endId) {
            this.startId = startId;
            this.endId = endId;
        }

        @Override
        public Boolean call() throws StorageException {

            HopsSession session = ClusterjConnector.getInstance().obtainSession();
            // Transaction tx = session.currentTransaction();
            //tx.begin();

            HopsQueryBuilder qb = session.getQueryBuilder();
            //Delete inodes with status=2 or status=3
            HopsQueryDomainType<INodeAttributesDTO> deleteInodeAttributes = qb.createQueryDefinition(INodeAttributesDTO.class);
            HopsPredicate pred1 = deleteInodeAttributes.get(INodeAttributesTableDef.STATUS).equal(deleteInodeAttributes.param("statusParam2"));
            HopsPredicate pred2 = deleteInodeAttributes.get(INodeAttributesTableDef.STATUS).equal(deleteInodeAttributes.param("statusParam3"));
            HopsPredicate pred3 = deleteInodeAttributes.get(INodeAttributesTableDef.ID).greaterEqual(deleteInodeAttributes.param("startId"));
            HopsPredicate pred4 = deleteInodeAttributes.get(INodeAttributesTableDef.ID).lessEqual(deleteInodeAttributes.param("endId"));
            HopsPredicate pred5 = pred3.and(pred4);
            HopsPredicate pred6 = pred1.or(pred2);
            deleteInodeAttributes.where(pred5.and(pred6));
            HopsQuery<INodeAttributesDTO> query = session.createQuery(deleteInodeAttributes);
            query.setParameter("statusParam2", SnapShotConstants.Modified);
            query.setParameter("statusParam3", SnapShotConstants.New);
            query.setParameter("startId", startId);
            query.setParameter("endId", endId);

            query.deletePersistentAll();

            // tx.commit();
            session.flush();
            session.close();
            return true;
        }
    }

    private static class InodeAttributesPhase2Callable implements Callable {

        int startId, endId;
        final Lock lock;

        InodeAttributesPhase2Callable(int startId, int endId, Lock lock) {
            this.startId = startId;
            this.endId = endId;
            this.lock = lock;
        }

        @Override
        public Boolean call() throws StorageException, IOException {
            try {

                HopsSession session = ClusterjConnector.getInstance().obtainSession();
//            Transaction tx = session.currentTransaction();
//            tx.begin();
                //System.err.println(Thread.currentThread().getId() + ": Started. StartId=" + startId + ", endId=" + endId);

                HopsQueryBuilder qb = session.getQueryBuilder();
                HopsQueryDomainType<INodeAttributesDTO> updateInodes = qb.createQueryDefinition(INodeAttributesDTO.class);
                HopsPredicate pred3 = updateInodes.get(INodeAttributesTableDef.ID).lessEqual(updateInodes.param("startId"));
                HopsPredicate pred4 = updateInodes.get(INodeAttributesTableDef.ID).greaterEqual(updateInodes.param("endId"));

                updateInodes.where(pred3.and(pred4));

                HopsQuery<INodeAttributesDTO> UpdateQuery = session.createQuery(updateInodes);
                UpdateQuery.setParameter("startId", startId);
                UpdateQuery.setParameter("endId", endId);


                List<INodeAttributesDTO> results = UpdateQuery.getResultList();
                //System.out.println(Thread.currentThread().getId() + ": Result Size= " + results.size());
                List<INodeAttributesDTO> newResults = new ArrayList<INodeAttributesDTO>(results.size());

                INodeAttributesDTO newInodeAttributes;

                for (INodeAttributesDTO row : results) {
                    newInodeAttributes = session.newInstance(INodeAttributesDTO.class);

                    newInodeAttributes.setId(-row.getId());
                    newInodeAttributes.setNSQuota(row.getNSQuota());
                    newInodeAttributes.setNSCount(row.getNSCount());
                    newInodeAttributes.setDSQuota(row.getDSQuota());
                    newInodeAttributes.setDiskspace(row.getDiskspace());
                    newInodeAttributes.setStatus(row.getStatus());

                    newResults.add(newInodeAttributes);
                }

                synchronized (lock) {

                    session.makePersistentAll(newResults);
                }
                System.gc();

                HopsQueryBuilder qbd = session.getQueryBuilder();
                HopsQueryDomainType<INodeAttributesDTO> deleteInodesWithNegativeId = qbd.createQueryDefinition(INodeAttributesDTO.class);
                HopsPredicate pred3d = deleteInodesWithNegativeId.get(INodeAttributesTableDef.ID).lessEqual(deleteInodesWithNegativeId.param("startId"));
                HopsPredicate pred4d = deleteInodesWithNegativeId.get(INodeAttributesTableDef.ID).greaterEqual(deleteInodesWithNegativeId.param("endId"));

                deleteInodesWithNegativeId.where(pred3d.and(pred4d));
                HopsQuery<INodeAttributesDTO> queryToDeleteNegative = session.createQuery(deleteInodesWithNegativeId);
                queryToDeleteNegative.setParameter("startId", startId);
                queryToDeleteNegative.setParameter("endId", endId);

                synchronized (lock) {
                    queryToDeleteNegative.deletePersistentAll();
                }

                session.flush();
                session.close();

            } catch (Throwable e) {
                throw new IOException(e);
            }
            return true;

        }
    }

    private static class InodesPhase1Callable implements Callable {

        int startId, endId;

        InodesPhase1Callable(int startId, int endId) {
            this.startId = startId;
            this.endId = endId;
        }

        @Override
        public Boolean call() throws StorageException {

            HopsSession session = ClusterjConnector.getInstance().obtainSession();
            // Transaction tx = session.currentTransaction();
            //tx.begin();

            HopsQueryBuilder qb = session.getQueryBuilder();
            //Delete inodes with status=2 or status=3
            HopsQueryDomainType<InodeDTO> deleteInodes = qb.createQueryDefinition(InodeDTO.class);
            HopsPredicate pred1 = deleteInodes.get(INodeTableDef.STATUS).equal(deleteInodes.param("statusParam2"));
            HopsPredicate pred2 = deleteInodes.get(INodeTableDef.STATUS).equal(deleteInodes.param("statusParam3"));
            HopsPredicate pred3 = deleteInodes.get(INodeTableDef.ID).greaterEqual(deleteInodes.param("startId"));
            HopsPredicate pred4 = deleteInodes.get(INodeTableDef.ID).lessEqual(deleteInodes.param("endId"));
            HopsPredicate pred5 = pred3.and(pred4);
            HopsPredicate pred6 = pred1.or(pred2);
            deleteInodes.where(pred5.and(pred6));
            HopsQuery<InodeDTO> query = session.createQuery(deleteInodes);
            query.setParameter("statusParam2", SnapShotConstants.Modified);
            query.setParameter("statusParam3", SnapShotConstants.New);
            query.setParameter("startId", startId);
            query.setParameter("endId", endId);


            query.deletePersistentAll();

            // tx.commit();
            session.flush();
            session.close();
            return true;
        }
    }

    private static class InodesPhase2Callable implements Callable {

        int startId, endId;

        InodesPhase2Callable(int startId, int endId) {
            this.startId = startId;
            this.endId = endId;
        }

        @Override
        public Boolean call() throws StorageException {

            HopsSession session = ClusterjConnector.getInstance().obtainSession();
            HopsQueryBuilder qb = session.getQueryBuilder();
            HopsQueryDomainType<InodeDTO> updateInodes = qb.createQueryDefinition(InodeDTO.class);

            HopsPredicate pred4 = updateInodes.get(INodeTableDef.ISDELETED).equal(updateInodes.param("isDeletedParam"));
            HopsPredicate pred5 = updateInodes.get(INodeTableDef.ID).lessEqual(updateInodes.param("startId"));
            HopsPredicate pred6 = updateInodes.get(INodeTableDef.ID).greaterEqual(updateInodes.param("endId"));
            updateInodes.where(pred6.and(pred5).and(pred4));

            HopsQuery<InodeDTO> UpdateQuery = session.createQuery(updateInodes);

            UpdateQuery.setParameter("isDeletedParam", SnapShotConstants.isDeleted);
            UpdateQuery.setParameter("startId", startId);
            UpdateQuery.setParameter("endId", endId);

            List<InodeDTO> results = UpdateQuery.getResultList();
            List<InodeDTO> newResults = new ArrayList<InodeDTO>(results.size());
            List<InodeDTO> tobeDeleted = new ArrayList<InodeDTO>(results.size());

            int id;
            InodeDTO newInode;
            for (InodeDTO row : results) {
                if ((id = row.getParentId()) < 0) {
                    newInode = session.newInstance(InodeDTO.class);
                    newInode.setId(-row.getId());
                    //newInode.setName(row.getName().split("\\$DEL\\$")[0]);
                    newInode.setName(row.getName().split("\\$DEL:[0-9]+\\$", 2)[0]);
                    newInode.setParentId(-id);
                    newInode.setQuotaEnabled(row.getQuotaEnabled());
                    newInode.setModificationTime(row.getModificationTime());
                    newInode.setATime(row.getATime());
                    newInode.setPermission(row.getPermission());
                    newInode.setUnderConstruction(row.getUnderConstruction());
                    newInode.setClientName(row.getClientName());
                    newInode.setClientMachine(row.getClientMachine());
                    newInode.setClientNode(row.getClientNode());
                    newInode.setGenerationStamp(row.getGenerationStamp());
                    newInode.setHeader(row.getHeader());
                    newInode.setSymlink(row.getSymlink());
                    newInode.setSubtreeLocked(row.getSubtreeLocked());
                    newInode.setSubtreeLockOwner(row.getSubtreeLockOwner());
                    newInode.setMetaEnabled(row.getMetaEnabled());
                    newInode.setSize(row.getSize());
                    newInode.setIsDeleted(SnapShotConstants.isNotDeleted);
                    newInode.setStatus(row.getStatus());

                    newResults.add(newInode);
                    tobeDeleted.add(row);
                }
            }

            if (!newResults.isEmpty())
                session.makePersistentAll(newResults);
            if (!tobeDeleted.isEmpty())
                session.deletePersistentAll(tobeDeleted);

            //System.out.println("SetIsDeleted from id=" + factor * BUFFER_SIZE + " ,to id=" + (factor + 1) * BUFFER_SIZE + ". Time= " + (end - start) / 1000 + "Seconds");

            // tx.commit();
            session.flush();
            session.close();
            return true;
        }
    }

    private static class InodesPhase3Callable implements Callable {

        private  final Log LOG = LogFactory.getLog(InodesPhase3Callable.class);
        int startId, endId;
        final Lock lock;

        InodesPhase3Callable(int startId, int endId, Lock lock) {
            System.out.println("The startId=" + startId + ".The endId=" + endId);
            this.startId = startId;
            this.endId = endId;
            this.lock = lock;
        }

        @Override
        public Boolean call() throws StorageException, IOException {
            try {
                System.out.println("call method is called");
                HopsSession session = ClusterjConnector.getInstance().obtainSession();
//            Transaction tx = session.currentTransaction();
//            tx.begin();
                //System.err.println(Thread.currentThread().getId() + ": Started. StartId=" + startId + ", endId=" + endId);

                HopsQueryBuilder qb = session.getQueryBuilder();
                HopsQueryDomainType<InodeDTO> updateInodes = qb.createQueryDefinition(InodeDTO.class);
                HopsPredicate pred3 = updateInodes.get(INodeTableDef.ID).lessEqual(updateInodes.param("startId"));
                HopsPredicate pred4 = updateInodes.get(INodeTableDef.ID).greaterEqual(updateInodes.param("endId"));

                updateInodes.where(pred3.and(pred4));


                HopsQuery<InodeDTO> UpdateQuery = session.createQuery(updateInodes);
                UpdateQuery.setParameter("startId", startId);
                UpdateQuery.setParameter("endId", endId);


                List<InodeDTO> results = UpdateQuery.getResultList();
                //System.out.println(Thread.currentThread().getId() + ": Result Size= " + results.size());
                List<InodeDTO> newResults = new ArrayList<InodeDTO>(results.size());
                System.out.println("Query is executed");
                InodeDTO newInode;

                for (InodeDTO row : results) {
                    newInode = session.newInstance(InodeDTO.class);

                    newInode.setId(-row.getId());
                    newInode.setName(row.getName());
                    newInode.setParentId(-row.getParentId());
                    newInode.setQuotaEnabled(row.getQuotaEnabled());
                    newInode.setModificationTime(row.getModificationTime());
                    newInode.setATime(row.getATime());
                    newInode.setPermission(row.getPermission());
                    newInode.setUnderConstruction(row.getUnderConstruction());
                    newInode.setClientName(row.getClientName());
                    newInode.setClientMachine(row.getClientMachine());
                    newInode.setClientNode(row.getClientNode());
                    newInode.setGenerationStamp(row.getGenerationStamp());
                    newInode.setHeader(row.getHeader());
                    newInode.setSymlink(row.getSymlink());
                    newInode.setSubtreeLocked(row.getSubtreeLocked());
                    newInode.setSubtreeLockOwner(row.getSubtreeLockOwner());
                    newInode.setMetaEnabled(row.getMetaEnabled());
                    newInode.setSize(row.getSize());
                    newInode.setIsDeleted(row.getIsDeleted());
                    newInode.setStatus(row.getStatus());

                    newResults.add(newInode);
                }
                System.gc();
                synchronized (lock) {
                    // System.out.println(Thread.currentThread().getId() + ": Acquired Lock. rows to write "+newResults.size());
                    session.makePersistentAll(newResults);
                }

                HopsQueryBuilder qbd = session.getQueryBuilder();
                HopsQueryDomainType<InodeDTO> deleteInodesWithNegativeId = qbd.createQueryDefinition(InodeDTO.class);
                HopsPredicate pred3d = deleteInodesWithNegativeId.get(INodeTableDef.ID).lessEqual(deleteInodesWithNegativeId.param("startId"));
                HopsPredicate pred4d = deleteInodesWithNegativeId.get(INodeTableDef.ID).greaterEqual(deleteInodesWithNegativeId.param("endId"));

                deleteInodesWithNegativeId.where(pred3d.and(pred4d));
                HopsQuery<InodeDTO> queryToDeleteNegative = session.createQuery(deleteInodesWithNegativeId);
                queryToDeleteNegative.setParameter("startId", startId);
                queryToDeleteNegative.setParameter("endId", endId);
                System.out.println("Deleting all the negative rows");
                synchronized (lock) {
                    queryToDeleteNegative.deletePersistentAll();
                }
                System.out.println("Deleted all the negative rows");
                session.flush();
                session.close();

            } catch (Throwable e) {
                throw new IOException(e);
            }
            return true;

        }
    }

    private static class Lock {
    }
}