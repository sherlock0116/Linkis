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

package org.apache.linkis.cli.application.interactor.job.builder;

import org.apache.linkis.cli.application.constants.AppConstants;
import org.apache.linkis.cli.application.constants.AppKeys;
import org.apache.linkis.cli.application.constants.LinkisKeys;
import org.apache.linkis.cli.application.interactor.job.LinkisSubmitJob;
import org.apache.linkis.cli.application.interactor.job.data.LinkisJobData;
import org.apache.linkis.cli.application.interactor.job.data.LinkisJobDataImpl;
import org.apache.linkis.cli.application.interactor.job.desc.LinkisSubmitDesc;
import org.apache.linkis.cli.application.observer.listener.LinkisClientListener;
import org.apache.linkis.cli.application.operator.ujes.LinkisJobOperator;
import org.apache.linkis.cli.application.utils.ExecutionUtils;
import org.apache.linkis.cli.common.entity.present.PresentWay;
import org.apache.linkis.cli.common.exception.LinkisClientRuntimeException;
import org.apache.linkis.cli.common.exception.error.ErrorLevel;
import org.apache.linkis.cli.core.exception.error.CommonErrMsg;
import org.apache.linkis.cli.core.interactor.job.JobBuilder;
import org.apache.linkis.cli.core.operator.JobOperatorFactory;
import org.apache.linkis.cli.core.present.PresentModeImpl;
import org.apache.linkis.cli.core.present.PresentWayImpl;

import org.apache.commons.lang3.StringUtils;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LinkisSubmitJobBuilder extends JobBuilder {
  private static Logger logger = LoggerFactory.getLogger(LinkisSubmitJobBuilder.class);

  private LinkisClientListener logListener;
  private Boolean isAsync = false;

  public LinkisSubmitJobBuilder setLogListener(LinkisClientListener observer) {
    this.logListener = observer;
    return this;
  }

  public LinkisSubmitJobBuilder setAsync(Boolean async) {
    isAsync = async;
    return this;
  }

  @Override
  protected LinkisSubmitJob getTargetNewInstance() {
    return new LinkisSubmitJob();
  }

  @Override
  protected LinkisSubmitDesc buildJobDesc() {
    LinkisSubmitDesc desc = new LinkisSubmitDesc();

    Map<String, Object> confMap = stdVarAccess.getVar(Map.class, AppKeys.JOB_PARAM_CONF);
    Map<String, Object> runtimeMap = stdVarAccess.getVar(Map.class, AppKeys.JOB_PARAM_RUNTIME);
    Map<String, Object> varMap = stdVarAccess.getVar(Map.class, AppKeys.JOB_PARAM_VAR);
    Map<String, Object> labelMap = stdVarAccess.getVar(Map.class, AppKeys.JOB_LABEL);
    Map<String, Object> sourceMap = stdVarAccess.getVar(Map.class, AppKeys.JOB_SOURCE);
    Map<String, Object> executionMap = stdVarAccess.getVar(Map.class, AppKeys.JOB_EXEC);

    confMap = confMap == null ? new HashMap<>() : confMap;
    runtimeMap = runtimeMap == null ? new HashMap<>() : runtimeMap;
    varMap = varMap == null ? new HashMap<>() : varMap;
    labelMap = labelMap == null ? new HashMap<>() : labelMap;
    sourceMap = sourceMap == null ? new HashMap<>() : sourceMap;
    executionMap = executionMap == null ? new HashMap<>() : executionMap;

    /** remove key prefix of all keys in map type params. e.g. kv in confMap, labelMap etc. */
    confMap = ProcessKeyUtils.removePrefixForKeysInMap(confMap);
    runtimeMap = ProcessKeyUtils.removePrefixForKeysInMap(runtimeMap);
    labelMap = ProcessKeyUtils.removePrefixForKeysInMap(labelMap);
    sourceMap = ProcessKeyUtils.removePrefixForKeysInMap(sourceMap);
    executionMap = ProcessKeyUtils.removePrefixForKeysInMap(executionMap);

    /** remove key prefix of non-map type params */
    for (String key : stdVarAccess.getAllVarKeys()) {
      Object val = stdVarAccess.getVar(Object.class, key);
      if (!(val instanceof Map) && val != null) {
        // note that we allow it to overwrite existing values in map
        if (StringUtils.startsWithIgnoreCase(key, AppKeys.JOB_PARAM_CONF)) {
          ProcessKeyUtils.removePrefixAndPutValToMap(confMap, key, val, AppKeys.JOB_PARAM_CONF);
        } else if (StringUtils.startsWithIgnoreCase(key, AppKeys.JOB_PARAM_VAR)) {
          ProcessKeyUtils.removePrefixAndPutValToMap(varMap, key, val, AppKeys.JOB_PARAM_VAR);
        } else if (StringUtils.startsWithIgnoreCase(key, AppKeys.JOB_PARAM_RUNTIME)) {
          ProcessKeyUtils.removePrefixAndPutValToMap(
              runtimeMap, key, val, AppKeys.JOB_PARAM_RUNTIME);
        } else if (StringUtils.startsWithIgnoreCase(key, AppKeys.JOB_EXEC)) {
          ProcessKeyUtils.removePrefixAndPutValToMap(executionMap, key, val, AppKeys.JOB_EXEC);
        } else if (StringUtils.startsWithIgnoreCase(key, AppKeys.JOB_LABEL)) {
          ProcessKeyUtils.removePrefixAndPutValToMap(labelMap, key, val, AppKeys.JOB_LABEL);
        } else if (StringUtils.startsWithIgnoreCase(key, AppKeys.JOB_SOURCE)) {
          ProcessKeyUtils.removePrefixAndPutValToMap(sourceMap, key, val, AppKeys.JOB_SOURCE);
        } else if (StringUtils.startsWithIgnoreCase(key, AppKeys.LINKIS_CLIENT_COMMON)) {
          // do nothing
        } else {
          //        confMap.put(key, stdVarAccess.getVar(Object.class, key));
        }
      }
    }

    String creator;
    if (!isAsync) {
      creator =
          stdVarAccess.getVarOrDefault(
              String.class, AppKeys.JOB_COMMON_CREATOR, AppConstants.JOB_CREATOR_DEFAULT);
    } else {
      creator =
          stdVarAccess.getVarOrDefault(
              String.class, AppKeys.JOB_COMMON_CREATOR, AppConstants.JOB_CREATOR_ASYNC_DEFAULT);
    }
    String code = stdVarAccess.getVar(String.class, AppKeys.JOB_EXEC_CODE);
    String engineType = stdVarAccess.getVar(String.class, AppKeys.JOB_LABEL_ENGINE_TYPE);
    String runType = stdVarAccess.getVar(String.class, AppKeys.JOB_LABEL_CODE_TYPE);
    String scriptPath =
        stdVarAccess.getVarOrDefault(String.class, AppKeys.JOB_SOURCE_SCRIPT_PATH, "LinkisCli");

    String osUser = sysVarAccess.getVar(String.class, AppKeys.LINUX_USER_KEY);
    String[] adminUsers = StringUtils.split(AppKeys.ADMIN_USERS, ',');
    Set<String> adminSet = new HashSet<>();
    for (String admin : adminUsers) {
      adminSet.add(admin);
    }
    String submitUsr = ExecutionUtils.getSubmitUser(stdVarAccess, osUser, adminSet);
    String proxyUsr = ExecutionUtils.getProxyUser(stdVarAccess, submitUsr, adminSet);

    String enableExecuteOnce =
        stdVarAccess.getVarOrDefault(String.class, AppKeys.JOB_LABEL_EXECUTEONCE, "true");
    // default executeOnce-mode
    if (Boolean.parseBoolean(enableExecuteOnce)) {
      labelMap.put(LinkisKeys.KEY_EXECUTEONCE, "");
    } else {
      labelMap.remove(LinkisKeys.KEY_EXECUTEONCE);
    }
    String codePath = stdVarAccess.getVar(String.class, AppKeys.JOB_COMMON_CODE_PATH);
    Object extraArgsObj = stdVarAccess.getVar(Object.class, AppKeys.JOB_EXTRA_ARGUMENTS);
    if (extraArgsObj != null
        && extraArgsObj instanceof String[]
        && StringUtils.isBlank(code)
        && StringUtils.isBlank(codePath)) {
      String[] extraArgs = (String[]) extraArgsObj;
      codePath = extraArgs[0];
      if (extraArgs.length > 1) {
        runtimeMap.put(
            LinkisKeys.EXTRA_ARGUMENTS, Arrays.copyOfRange(extraArgs, 1, extraArgs.length));
      }
    }

    if (StringUtils.isBlank(code) && StringUtils.isNotBlank(codePath)) {
      code = ExecutionUtils.readFile(codePath);
    }

    executionMap.put(LinkisKeys.KEY_CODE, code);
    labelMap.put(LinkisKeys.KEY_ENGINETYPE, engineType);
    labelMap.put(LinkisKeys.KEY_CODETYPE, runType);
    labelMap.put(LinkisKeys.KEY_USER_CREATOR, proxyUsr + "-" + creator);
    sourceMap.put(LinkisKeys.KEY_SCRIPT_PATH, scriptPath);
    runtimeMap.put(LinkisKeys.KEY_HIVE_RESULT_DISPLAY_TBALE, true);

    desc.setCreator(creator);
    desc.setParamConfMap(confMap);
    desc.setParamRunTimeMap(runtimeMap);
    desc.setParamVarsMap(varMap);
    desc.setLabelMap(labelMap);
    desc.setSourceMap(sourceMap);
    desc.setExecutionMap(executionMap);
    desc.setSubmitUser(submitUsr);
    desc.setProxyUser(proxyUsr);

    return desc;
  }

  @Override
  protected LinkisJobData buildJobData() {
    LinkisJobDataImpl data = new LinkisJobDataImpl();
    if (logListener == null) {
      logger.warn("logListener is not registered, will not be able to display log");
    } else {
      data.registerincLogListener(logListener);
    }
    return data;
  }

  @Override
  protected LinkisJobOperator buildJobOperator() {
    LinkisJobOperator oper;
    try {
      oper = (LinkisJobOperator) JobOperatorFactory.getReusable(AppKeys.REUSABLE_UJES_CLIENT);
    } catch (Exception e) {
      throw new LinkisClientRuntimeException(
          "BLD0012",
          ErrorLevel.ERROR,
          CommonErrMsg.BuilderBuildErr,
          "Failed to get a valid operator.",
          e);
    }
    return oper;
  }

  @Override
  protected PresentWay buildPresentWay() {
    PresentWayImpl presentWay = new PresentWayImpl();
    String outputPath = stdVarAccess.getVar(String.class, AppKeys.LINKIS_CLIENT_COMMON_OUTPUT_PATH);

    presentWay.setPath(outputPath);
    presentWay.setMode(PresentModeImpl.STDOUT);
    presentWay.setDisplayMetaAndLogo(
        stdVarAccess.getVarOrDefault(Boolean.class, AppKeys.LINKIS_COMMON_DIAPLAY_META_LOGO, true));
    if (StringUtils.isNotBlank(outputPath)) {
      presentWay.setMode(PresentModeImpl.TEXT_FILE);
    }

    return presentWay;
  }

  @Override
  public LinkisSubmitJob build() {
    ((LinkisSubmitJob) targetObj).setJobDesc(buildJobDesc());
    ((LinkisSubmitJob) targetObj).setJobData(buildJobData());
    ((LinkisSubmitJob) targetObj).setAsync(isAsync);
    targetObj.setOperator(buildJobOperator());
    targetObj.setPresentWay(buildPresentWay());
    return (LinkisSubmitJob) super.build();
  }
}
