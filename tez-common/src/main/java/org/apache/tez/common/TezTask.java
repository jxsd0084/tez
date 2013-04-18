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
package org.apache.tez.common;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import javax.crypto.SecretKey;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.util.Progress;
import org.apache.tez.engine.api.Partitioner;
import org.apache.tez.engine.api.Processor;
import org.apache.tez.engine.records.TezDAGID;
import org.apache.tez.engine.records.TezTaskAttemptID;

public abstract class TezTask implements Writable {

  // Serialized Fields
  private TezTaskAttemptID taskAttemptId;
  private String user;
  private String jobName;
  private String vertexName;
  


  protected SecretKey jobTokenSecret;
  protected TezTaskReporter reporter;
  protected Partitioner partitioner;
  protected Processor combineProcessor;
  protected TezTaskStatus status;
  protected Progress progress = new Progress();

  public TezTask() {
  }

  public TezTask(TezTaskAttemptID taskAttemptID, String user, String jobName,
      String vertexName) {
    this.taskAttemptId = taskAttemptID;
    this.user = user;
    this.jobName = jobName;
    this.vertexName = vertexName;
  }

  public TezTaskAttemptID getTaskAttemptId() {
    return taskAttemptId;
  }

  public Progress getProgress() {
    return progress;
  }

  public TezDAGID getDAGID() {
    return taskAttemptId.getTaskID().getVertexID().getDAGId();
  }

  public String getUser() {
    return user;
  }

  public String getJobName() {
    return jobName;
  }
  
  public String getVertexName() {
    return this.vertexName;
  }

  public SecretKey getJobTokenSecret() {
    return jobTokenSecret;
  }

  public void setJobTokenSecret(SecretKey jobTokenSecret) {
    this.jobTokenSecret = jobTokenSecret;
  }

  public TezTaskStatus getStatus() {
    return status;
  }

  public TezTaskReporter getTaskReporter() {
    return reporter;
  }

  public Processor getCombineProcessor() {
    return combineProcessor;
  }

  public Partitioner getPartitioner() {
    return partitioner;
  }

  

  public void statusUpdate() throws IOException, InterruptedException {
  }

  @Override
  public void write(DataOutput out) throws IOException {
    taskAttemptId.write(out);
    Text.writeString(out, user);
    Text.writeString(out, jobName);
    Text.writeString(out, vertexName);
  }

  public void readFields(DataInput in) throws IOException {
    taskAttemptId = TezTaskAttemptID.read(in);
    user = Text.readString(in);
    jobName = Text.readString(in);
    vertexName = Text.readString(in);
  }

}
