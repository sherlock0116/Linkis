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

package org.apache.linkis.instance.label.service.impl;

import org.apache.linkis.common.ServiceInstance;
import org.apache.linkis.common.utils.Utils;
import org.apache.linkis.instance.label.async.AsyncConsumerQueue;
import org.apache.linkis.instance.label.async.GenericAsyncConsumerQueue;
import org.apache.linkis.instance.label.conf.InsLabelConf;
import org.apache.linkis.instance.label.dao.InsLabelRelationDao;
import org.apache.linkis.instance.label.dao.InstanceInfoDao;
import org.apache.linkis.instance.label.dao.InstanceLabelDao;
import org.apache.linkis.instance.label.entity.InsPersistenceLabel;
import org.apache.linkis.instance.label.entity.InstanceInfo;
import org.apache.linkis.instance.label.exception.InstanceErrorException;
import org.apache.linkis.instance.label.service.InsLabelAccessService;
import org.apache.linkis.instance.label.service.annotation.AdapterMode;
import org.apache.linkis.instance.label.vo.InsPersistenceLabelSearchVo;
import org.apache.linkis.manager.label.builder.factory.LabelBuilderFactory;
import org.apache.linkis.manager.label.builder.factory.LabelBuilderFactoryContext;
import org.apache.linkis.manager.label.entity.Label;
import org.apache.linkis.manager.label.utils.LabelUtils;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.commons.lang3.math.NumberUtils.isCreatable;

@AdapterMode
@EnableAspectJAutoProxy(proxyTargetClass = true, exposeProxy = true)
@Service
public class DefaultInsLabelService implements InsLabelAccessService {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultInsLabelService.class);
  private static Set<String> modifiableLabelKeyList;
  @Autowired private InstanceLabelDao instanceLabelDao;
  @Autowired private InstanceInfoDao instanceDao;
  @Autowired private InsLabelRelationDao insLabelRelationDao;
  @Autowired private InstanceInfoDao instanceInfoDao;
  private AsyncConsumerQueue<InsPersistenceLabel> asyncRemoveLabelQueue;
  private InsLabelAccessService selfService;
  private AtomicBoolean asyncQueueInit = new AtomicBoolean(false);
  private LabelBuilderFactory labelBuilderFactory =
      LabelBuilderFactoryContext.getLabelBuilderFactory();

  /** init method */
  private synchronized void initQueue() {
    if (!asyncQueueInit.get()) {
      selfService = (InsLabelAccessService) AopContext.currentProxy();
      LOG.info("SelfService: [" + this.getClass().getName() + "]");
      asyncRemoveLabelQueue =
          new GenericAsyncConsumerQueue<>(InsLabelConf.ASYNC_QUEUE_CAPACITY.getValue());
      asyncRemoveLabelQueue.consumer(
          InsLabelConf.ASYNC_QUEUE_CONSUME_BATCH_SIZE.getValue(),
          InsLabelConf.ASYNC_QUEUE_CONSUME_INTERVAL.getValue(),
          TimeUnit.SECONDS,
          insLabels -> {
            selfService.removeLabelsIfNotRelation(insLabels);
          });
      asyncQueueInit.set(true);
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void attachLabelToInstance(Label<?> label, ServiceInstance serviceInstance)
      throws InstanceErrorException {
    attachLabelsToInstance(Collections.singletonList(label), serviceInstance);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void attachLabelsToInstance(
      List<? extends Label<?>> labels, ServiceInstance serviceInstance)
      throws InstanceErrorException {
    List<InsPersistenceLabel> insLabels = toInsPersistenceLabels(labels);
    List<InsPersistenceLabel> labelsNeedInsert = filterLabelNeededInsert(insLabels, true);
    if (!labelsNeedInsert.isEmpty()) {
      LOG.info("Persist labels: [" + LabelUtils.Jackson.toJson(labels, null) + "]");
      doInsertInsLabels(labelsNeedInsert);
    }
    LOG.info("Insert/Update service instance info: [" + serviceInstance + "]");
    doInsertInstance(serviceInstance);
    List<Integer> insLabelIds =
        insLabels.stream().map(InsPersistenceLabel::getId).collect(Collectors.toList());
    LOG.info(
        "Build relation between labels: "
            + LabelUtils.Jackson.toJson(insLabelIds, null)
            + " and instance: ["
            + serviceInstance.getInstance()
            + "]");
    batchOperation(
        insLabelIds,
        subInsLabelIds ->
            insLabelRelationDao.insertRelations(serviceInstance.getInstance(), subInsLabelIds),
        InsLabelConf.DB_PERSIST_BATCH_SIZE.getValue());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void refreshLabelsToInstance(
      List<? extends Label<?>> labels, ServiceInstance serviceInstance)
      throws InstanceErrorException {
    List<InsPersistenceLabel> insLabels = toInsPersistenceLabels(labels);
    // Label candidate to be removed
    List<InsPersistenceLabel> labelsCandidateRemoved =
        insLabelRelationDao.searchLabelsByInstance(serviceInstance.getInstance());
    if (!labelsCandidateRemoved.isEmpty()) {
      labelsCandidateRemoved.removeAll(insLabels);
    }
    LOG.info("Drop relationships related by instance: [" + serviceInstance.getInstance() + "]");
    insLabelRelationDao.dropRelationsByInstance(serviceInstance.getInstance());
    // Attach labels to instance
    attachLabelsToInstance(insLabels, serviceInstance);
    // Async to delete labels that have no relationship
    /*     if(!labelsCandidateRemoved.isEmpty()){
        if(!asyncQueueInit.get()) {
            initQueue();
        }
        labelsCandidateRemoved.forEach( label -> {
            if(!asyncRemoveLabelQueue.offer(label)){
                LOG.warn("Async queue for removing labels maybe full. current size: [" + asyncRemoveLabelQueue.size() + "]");
            }
        });
    }*/
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeLabelsFromInstance(ServiceInstance serviceInstance) {
    // Label candidate to be removed
    List<InsPersistenceLabel> labelsCandidateRemoved =
        insLabelRelationDao.searchLabelsByInstance(serviceInstance.getInstance());
    LOG.info("Drop relationships related by instance: [" + serviceInstance.getInstance() + "]");
    insLabelRelationDao.dropRelationsByInstance(serviceInstance.getInstance());
    // Async to delete labels that have no relationship
    //        if(!labelsCandidateRemoved.isEmpty()){
    //            labelsCandidateRemoved.forEach( label -> {
    //                if(!asyncQueueInit.get()) {
    //                    initQueue();
    //                }
    //                if(!asyncRemoveLabelQueue.offer(label)){
    //                    LOG.warn("Async queue for removing labels maybe full. current size: ["
    // + asyncRemoveLabelQueue.size() + "]");
    //                }
    //            });
    //        }
  }

  @Override
  public List<ServiceInstance> searchInstancesByLabels(List<? extends Label<?>> labels) {
    return searchInstancesByLabels(labels, Label.ValueRelation.ALL);
  }

  @Override
  public List<ServiceInstance> searchInstancesByLabels(
      List<? extends Label<?>> labels, Label.ValueRelation relation) {
    List<InsPersistenceLabel> insLabels = toInsPersistenceLabels(labels);
    if (!insLabels.isEmpty()) {
      List<InstanceInfo> instanceInfoList = insLabelRelationDao.searchInsDirectByLabels(insLabels);
      return instanceInfoList.stream()
          .map(instanceInfo -> (ServiceInstance) instanceInfo)
          .collect(Collectors.toList());
    }
    return Collections.emptyList();
  }

  @Override
  public List<ServiceInstance> searchUnRelateInstances(ServiceInstance serviceInstance) {
    if (null != serviceInstance) {
      return insLabelRelationDao.searchUnRelateInstances(new InstanceInfo(serviceInstance)).stream()
          .map(instanceInfo -> (ServiceInstance) instanceInfo)
          .collect(Collectors.toList());
    }
    return Collections.emptyList();
  }

  @Override
  public List<ServiceInstance> searchLabelRelatedInstances(ServiceInstance serviceInstance) {
    if (null != serviceInstance) {
      return insLabelRelationDao.searchLabelRelatedInstances(new InstanceInfo(serviceInstance))
          .stream()
          .map(instanceInfo -> (ServiceInstance) instanceInfo)
          .collect(Collectors.toList());
    }
    return Collections.emptyList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeLabelsIfNotRelation(List<? extends Label<?>> labels) {
    List<InsPersistenceLabel> insLabels = toInsPersistenceLabels(labels);
    insLabels.forEach(
        insLabel -> {
          if (Optional.ofNullable(insLabel.getId()).isPresent()) {
            insLabel = instanceLabelDao.selectForUpdate(insLabel.getId());
          } else {
            insLabel =
                instanceLabelDao.searchForUpdate(insLabel.getLabelKey(), insLabel.getStringValue());
          }
          if (null != insLabel) {
            Integer exist = insLabelRelationDao.existRelations(insLabel.getId());
            // Not exist
            if (null == exist) {
              LOG.info("Remove information of instance label: [" + insLabel.toString() + "]");
              instanceLabelDao.remove(insLabel);
              // instanceLabelDao.doRemoveKeyValues(insLabel.getId());
            }
          }
        });
  }

  @Override
  public List<InstanceInfo> listAllInstanceWithLabel() {
    List<InstanceInfo> instances = insLabelRelationDao.listAllInstanceWithLabel();
    return instances;
  }

  @Override
  public List<ServiceInstance> getInstancesByNames(String appName) {
    return insLabelRelationDao.getInstancesByNames(appName);
  }

  @Override
  public void removeInstance(ServiceInstance serviceInstance) {
    instanceInfoDao.removeInstance(serviceInstance);
  }

  @Override
  public InstanceInfo getInstanceInfoByServiceInstance(ServiceInstance serviceInstance) {
    InstanceInfo instanceInfo = instanceInfoDao.getInstanceInfoByServiceInstance(serviceInstance);
    return instanceInfo;
  }

  @Override
  public void updateInstance(InstanceInfo instanceInfo) {
    instanceInfoDao.updateInstance(instanceInfo);
  }

  public String getEurekaURL() throws Exception {
    String eurekaURL = InsLabelConf.EUREKA_IPADDRESS.getValue();
    String realURL = eurekaURL;
    if (eurekaURL.isEmpty()) {
      String defaultZone = InsLabelConf.EUREKA_URL.getValue();
      String tmpURL = urlPreDeal(defaultZone);
      realURL = replaceEurekaURL(tmpURL);
    }
    return realURL;
  }

  private String urlPreDeal(String defaultZone) {
    if (defaultZone.contains(",")) {
      defaultZone = defaultZone.split(",")[0];
    }
    String url = defaultZone;
    if (defaultZone.matches("https?://[-A-Za-z0-9+&@#/%?=~_|!:,.;]+[-A-Za-z0-9+&@#/%=~_|]")) {
      if (defaultZone.toLowerCase().contains("eureka")) {
        url = defaultZone.split("eureka", 2)[0];
      }
    }
    return url;
  }

  private String replaceEurekaURL(String tmpURL) throws URISyntaxException, UnknownHostException {
    URI uri = new URI(tmpURL);
    if (isIPAddress(uri.getHost())) {
      if (uri.getHost().equals("127.0.0.1")) {
        String ip = Utils.getLocalHostname();
        return tmpURL.replace("127.0.0.1", ip);
      } else {
        return tmpURL;
      }
    } else {
      String hostname = uri.getHost();
      InetAddress address = InetAddress.getByName(hostname);
      String realAddress = address.getHostAddress();
      return tmpURL.replace(hostname, realAddress);
    }
  }

  private boolean isIPAddress(String tmpURL) {
    String[] urlArray = tmpURL.split(".");
    if (urlArray.length == 4) {
      if (isCreatable(urlArray[0])
          && isCreatable(urlArray[1])
          && isCreatable(urlArray[2])
          && isCreatable(urlArray[3])) {
        return true;
      }
    }
    return false;
  }

  /**
   * Filter labels
   *
   * @param insLabels labels
   * @return
   */
  private List<InsPersistenceLabel> filterLabelNeededInsert(
      List<InsPersistenceLabel> insLabels, boolean needLock) {
    List<InsPersistenceLabelSearchVo> labelSearchVos =
        insLabels.stream().map(InsPersistenceLabelSearchVo::new).collect(Collectors.toList());
    List<InsPersistenceLabel> storedLabels = new ArrayList<>();
    if (!labelSearchVos.isEmpty()) {
      storedLabels = instanceLabelDao.search(labelSearchVos);
    }
    if (!storedLabels.isEmpty()) {
      List<InsPersistenceLabel> labelsNeedInsert = new ArrayList<>(insLabels);
      List<InsPersistenceLabel> finalStoredLabels = storedLabels;
      labelsNeedInsert.removeIf(
          labelNeedInsert -> {
            for (InsPersistenceLabel storedLabel : finalStoredLabels) {
              if (labelNeedInsert.equals(storedLabel)) {
                Integer labelId = storedLabel.getId();
                labelNeedInsert.setId(labelId);
                if (needLock) {
                  // Update time
                  return instanceLabelDao.updateForLock(labelId) >= 0;
                }
                return true;
              }
            }
            return false;
          });
      return labelsNeedInsert;
    }
    return insLabels;
  }

  /**
   * Operation of inserting labels
   *
   * @param insLabels labels
   */
  private void doInsertInsLabels(List<InsPersistenceLabel> insLabels) {
    // Try to insert, use ON DUPLICATE KEY on mysql
    batchOperation(
        insLabels,
        subInsLabels -> instanceLabelDao.insertBatch(subInsLabels),
        InsLabelConf.DB_PERSIST_BATCH_SIZE.getValue());
    /*List<InsPersistenceLabelValue> labelValues =
            insLabels.stream()
                    .flatMap(
                            insLabel -> {
                                if (!Optional.ofNullable(insLabel.getId()).isPresent()) {
                                    throw new IllegalArgumentException(
                                            "Cannot get the generated id from bulk insertion, please check your mybatis version");
                                }
                                return insLabel.getValue().entrySet().stream()
                                        .map(
                                                entry ->
                                                        new InsPersistenceLabelValue(
                                                                insLabel.getId(),
                                                                entry.getKey(),
                                                                entry.getValue()));
                            })
                    .collect(Collectors.toList());
    batchOperation(
            labelValues,
            subLabelValues -> instanceLabelDao.doInsertKeyValues(subLabelValues),
            InsLabelConf.DB_PERSIST_BATCH_SIZE.getValue());*/
  }

  /**
   * Operation of inserting instances
   *
   * @param serviceInstance service instance
   */
  private void doInsertInstance(ServiceInstance serviceInstance) throws InstanceErrorException {
    // ON DUPLICATE KEY
    try {
      instanceDao.insertOne(new InstanceInfo(serviceInstance));
    } catch (Exception e) {
      throw new InstanceErrorException("Failed to insert service instance", e);
    }
  }

  /**
   * Transform to <em>InsPersistenceLabel</em>
   *
   * @param labels
   * @return
   */
  private List<InsPersistenceLabel> toInsPersistenceLabels(List<? extends Label<?>> labels) {
    LabelBuilderFactory builderFactory = LabelBuilderFactoryContext.getLabelBuilderFactory();
    return labels.stream()
        .map(
            label -> {
              if (label instanceof InsPersistenceLabel) {
                return (InsPersistenceLabel) label;
              }
              InsPersistenceLabel insLabel =
                  builderFactory.convertLabel(label, InsPersistenceLabel.class);
              insLabel.setStringValue(label.getStringValue());
              if (StringUtils.isNotBlank(insLabel.getStringValue())) {
                insLabel.setLabelValueSize(insLabel.getValue().size());
              }
              return insLabel;
            })
        .collect(Collectors.toList());
  }

  @SuppressWarnings("unchecked")
  private <T extends List<?>> void batchOperation(T input, Consumer<T> batchFunc, int batchSize) {
    int listLen = input.size();
    if (listLen > 0) {
      for (int from = 0; from < listLen; ) {
        int to = Math.min(from + batchSize, listLen);
        T subInput = (T) input.subList(from, to);
        batchFunc.accept(subInput);
        from = to;
      }
    }
  }

  public void markInstanceLabel(List<InstanceInfo> instances) {
    Set<String> keyList = LabelUtils.listAllUserModifiableLabel();
    for (InstanceInfo instance : instances) {
      List<InsPersistenceLabel> labels = instance.getLabels();
      if (!CollectionUtils.isEmpty(labels)) {
        for (InsPersistenceLabel label : labels) {
          if (keyList.contains(label.getLabelKey())) {
            label.setModifiable(true);
          }
        }
      }
    }
  }
}
