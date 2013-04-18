/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.tez.dag.app.rm.container;

import org.apache.hadoop.yarn.api.records.ContainerId;

public class AMContainerEventStopFailed extends AMContainerEvent {

  // TODO XXX Not being used for anything. May be useful if we rely less on
  // the RM informing the job about container failure.
  
  private final String message;

  public AMContainerEventStopFailed(ContainerId containerId, String message) {
    super(containerId, AMContainerEventType.C_NM_STOP_FAILED);
    this.message = message;
  }

  public String getMessage() {
    return this.message;
  }
}
