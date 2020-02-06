/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.procedure2.store.region;

import java.io.IOException;
import java.lang.management.MemoryType;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.ChoreService;
import org.apache.hadoop.hbase.CoordinatedStateManager;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.client.AsyncClusterConnection;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.io.util.MemorySizeUtil;
import org.apache.hadoop.hbase.master.cleaner.DirScanPool;
import org.apache.hadoop.hbase.procedure2.store.ProcedureStorePerformanceEvaluation;
import org.apache.hadoop.hbase.regionserver.ChunkCreator;
import org.apache.hadoop.hbase.regionserver.MemStoreLAB;
import org.apache.hadoop.hbase.util.CommonFSUtils;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.zookeeper.ZKWatcher;

public class RegionProcedureStorePerformanceEvaluation
  extends ProcedureStorePerformanceEvaluation<RegionProcedureStore> {

  private static final class MockServer implements Server {

    private final Configuration conf;

    private final ServerName serverName =
      ServerName.valueOf("localhost", 12345, System.currentTimeMillis());

    private final ChoreService choreService;

    private volatile boolean abort = false;

    public MockServer(Configuration conf) {
      this.conf = conf;
      this.choreService = new ChoreService("Cleaner-Chore-Service");
    }

    @Override
    public void abort(String why, Throwable e) {
      abort = true;
      choreService.shutdown();
    }

    @Override
    public boolean isAborted() {
      return abort;
    }

    @Override
    public void stop(String why) {
      choreService.shutdown();
    }

    @Override
    public boolean isStopped() {
      return false;
    }

    @Override
    public Configuration getConfiguration() {
      return conf;
    }

    @Override
    public ZKWatcher getZooKeeper() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Connection getConnection() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Connection createConnection(Configuration conf) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public AsyncClusterConnection getAsyncClusterConnection() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ServerName getServerName() {
      return serverName;
    }

    @Override
    public CoordinatedStateManager getCoordinatedStateManager() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChoreService getChoreService() {
      return choreService;
    }
  }

  private DirScanPool cleanerPool;

  @Override
  protected RegionProcedureStore createProcedureStore(Path storeDir) throws IOException {
    Pair<Long, MemoryType> pair = MemorySizeUtil.getGlobalMemStoreSize(conf);
    long globalMemStoreSize = pair.getFirst();
    boolean offheap = pair.getSecond() == MemoryType.NON_HEAP;
    float poolSizePercentage = offheap ? 1.0F :
      conf.getFloat(MemStoreLAB.CHUNK_POOL_MAXSIZE_KEY, MemStoreLAB.POOL_MAX_SIZE_DEFAULT);
    float initialCountPercentage =
      conf.getFloat(MemStoreLAB.CHUNK_POOL_INITIALSIZE_KEY, MemStoreLAB.POOL_INITIAL_SIZE_DEFAULT);
    int chunkSize = conf.getInt(MemStoreLAB.CHUNK_SIZE_KEY, MemStoreLAB.CHUNK_SIZE_DEFAULT);
    ChunkCreator.initialize(chunkSize, offheap, globalMemStoreSize, poolSizePercentage,
      initialCountPercentage, null);
    conf.setBoolean(RegionProcedureStore.USE_HSYNC_KEY, "hsync".equals(syncType));
    CommonFSUtils.setRootDir(conf, storeDir);
    cleanerPool = new DirScanPool(conf);
    return new RegionProcedureStore(new MockServer(conf), cleanerPool, (fs, apth) -> {
    });
  }

  @Override
  protected void printRawFormatResult(long timeTakenNs) {
    System.out.println(String.format("RESULT [%s=%s, %s=%s, %s=%s, %s=%s, " + "total_time_ms=%s]",
      NUM_PROCS_OPTION.getOpt(), numProcs, STATE_SIZE_OPTION.getOpt(), stateSize,
      SYNC_OPTION.getOpt(), syncType, NUM_THREADS_OPTION.getOpt(), numThreads, timeTakenNs));
  }

  @Override
  protected void preWrite(long procId) throws IOException {
  }

  @Override
  protected void postStop(RegionProcedureStore store) throws IOException {
    cleanerPool.shutdownNow();
  }

  public static void main(String[] args) throws IOException {
    RegionProcedureStorePerformanceEvaluation tool =
      new RegionProcedureStorePerformanceEvaluation();
    tool.setConf(HBaseConfiguration.create());
    tool.run(args);
  }
}
