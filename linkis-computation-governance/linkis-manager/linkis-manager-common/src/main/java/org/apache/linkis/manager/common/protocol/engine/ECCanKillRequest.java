/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.linkis.manager.common.protocol.engine;

import org.apache.linkis.common.ServiceInstance;
import org.apache.linkis.manager.label.entity.engine.EngineTypeLabel;
import org.apache.linkis.manager.label.entity.engine.UserCreatorLabel;

public class ECCanKillRequest implements EngineRequest {

  private String user;

  private ServiceInstance engineConnInstance;

  private EngineTypeLabel engineTypeLabel;

  private UserCreatorLabel userCreatorLabel;

  @Override
  public String getUser() {
    return user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  public ServiceInstance getEngineConnInstance() {
    return engineConnInstance;
  }

  public void setEngineConnInstance(ServiceInstance engineConnInstance) {
    this.engineConnInstance = engineConnInstance;
  }

  public EngineTypeLabel getEngineTypeLabel() {
    return engineTypeLabel;
  }

  public void setEngineTypeLabel(EngineTypeLabel engineTypeLabel) {
    this.engineTypeLabel = engineTypeLabel;
  }

  public UserCreatorLabel getUserCreatorLabel() {
    return userCreatorLabel;
  }

  public void setUserCreatorLabel(UserCreatorLabel userCreatorLabel) {
    this.userCreatorLabel = userCreatorLabel;
  }

  @Override
  public String toString() {
    return "ECCanKillRequest{"
        + "user='"
        + user
        + '\''
        + ", engineConnInstance="
        + engineConnInstance
        + ", engineTypeLabel="
        + engineTypeLabel
        + ", userCreatorLabel="
        + userCreatorLabel
        + '}';
  }
}
