/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.linkis.ujes.client.response

import org.apache.linkis.httpclient.dws.annotation.DWSHttpMessageResult
import org.apache.linkis.httpclient.dws.response.DWSResult
import org.apache.linkis.ujes.client.request.UserAction

import org.apache.commons.collections.CollectionUtils

import java.util

import scala.beans.BeanProperty
import scala.collection.JavaConversions._

@DWSHttpMessageResult("/api/rest_j/v\\d+/datasource/dbs")
class GetDBSResult extends DWSResult with UserAction {
  @BeanProperty var dbs: util.List[util.Map[String, String]] = _

  def getDBSName(): util.List[String] = {
    val dbsList = new util.ArrayList[String]()
    if (CollectionUtils.isNotEmpty(dbs)) {
      dbs.foreach { db =>
        dbsList.add(db.get("dbName"))
      }
    }
    dbsList
  }

}
