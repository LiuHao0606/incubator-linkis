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
 
package org.apache.linkis.entrance.cs

import org.apache.commons.lang.StringUtils
import org.apache.linkis.common.utils.Logging
import org.apache.linkis.cs.client.service.{CSNodeServiceImpl, CSVariableService, LinkisJobDataServiceImpl}
import org.apache.linkis.cs.client.utils.{ContextServiceUtils, SerializeHelper}
import org.apache.linkis.cs.common.entity.`object`.LinkisVariable
import org.apache.linkis.cs.common.entity.data.LinkisJobData
import org.apache.linkis.cs.common.entity.enumeration.{ContextScope, ContextType}
import org.apache.linkis.cs.common.entity.source.{CommonContextKey, LinkisWorkflowContextID}
import org.apache.linkis.cs.common.utils.CSCommonUtils
import org.apache.linkis.entrance.conf.EntranceConfiguration
import org.apache.linkis.entrance.execute.EntranceJob
import org.apache.linkis.governance.common.entity.job.JobRequest
import org.apache.linkis.manager.label.entity.Label
import org.apache.linkis.manager.label.entity.engine.UserCreatorLabel
import org.apache.linkis.manager.label.utils.LabelUtil
import org.apache.linkis.protocol.constants.TaskConstant
import org.apache.linkis.protocol.utils.TaskUtils
import org.apache.linkis.scheduler.queue.Job

import java.util
import scala.collection.JavaConversions._


object CSEntranceHelper extends Logging {


  def getContextInfo(params: util.Map[String, Any]): (String, String) = {

    val runtimeMap = params.get(TaskConstant.PARAMS_CONFIGURATION) match {
      case map: util.Map[String, AnyRef] => map.get(TaskConstant.PARAMS_CONFIGURATION_RUNTIME)
      case _ => null
    }

    if (null != runtimeMap) {
      runtimeMap match {
        case map: util.Map[String, AnyRef] =>
          val name = ContextServiceUtils.getNodeNameStrByMap(map)
          return (ContextServiceUtils.getContextIDStrByMap(map), name)
        case _ =>
      }
    }
    (null, null)
  }

  def setContextInfo(params: util.Map[String, Any], copyMap: util.Map[String, String]): Unit = {
    val (contextIDValueStr, nodeNameStr) = getContextInfo(params)
    if (StringUtils.isNotBlank(contextIDValueStr)) {
      copyMap.put(CSCommonUtils.CONTEXT_ID_STR, contextIDValueStr)
      copyMap.put(CSCommonUtils.NODE_NAME_STR, nodeNameStr)
    }
  }


  /**
    * register job id to cs
    *
    * @param job
    */
  def registerCSRSData(job: Job): Unit = {
    job match {
      case entranceJob: EntranceJob => {
        val (contextIDValueStr, nodeNameStr) = getContextInfo(entranceJob.getParams)
        info(s"registerCSRSData: nodeName:$nodeNameStr")
        if (StringUtils.isBlank(contextIDValueStr) || StringUtils.isBlank(nodeNameStr)) return null

        val contextKey = new CommonContextKey
        contextKey.setContextScope(ContextScope.PUBLIC)
        contextKey.setContextType(ContextType.DATA)
        contextKey.setKey(CSCommonUtils.NODE_PREFIX + nodeNameStr + CSCommonUtils.JOB_ID)
        entranceJob.getJobRequest match {
          case jobRequest: JobRequest =>
            val data = new LinkisJobData
            data.setJobID(jobRequest.getId)
            LinkisJobDataServiceImpl.getInstance().putLinkisJobData(contextIDValueStr, SerializeHelper.serializeContextKey(contextKey), data)
            info(s"(${contextKey.getKey} put ${jobRequest.getId} of jobId to cs)")
          case _ =>
        }
        info(s"registerCSRSData end: nodeName:$nodeNameStr")
      }
      case _ =>
    }
  }

  /**
    * initNodeCSInfo
    *
    * @param requestPersistTask
    * @return
    */
  def initNodeCSInfo(requestPersistTask: JobRequest): Unit = {

    val (contextIDValueStr, nodeNameStr) = getContextInfo(requestPersistTask.getParams.asInstanceOf[util.Map[String, Any]])

    if (StringUtils.isNotBlank(contextIDValueStr) && StringUtils.isNotBlank(nodeNameStr)) {
      info(s"init node($nodeNameStr) cs info")
      CSNodeServiceImpl.getInstance().initNodeCSInfo(contextIDValueStr, nodeNameStr)
    }
  }


  /**
    * reset creator by contextID information
    * 1. Not set If contextID does not exists
    * 2. If env of contextID are dev set  nodeexecution
    * 3. If env of contextID are prod set scheduler
    *
    * @param requestPersistTask
    */
  def resetCreator(requestPersistTask: JobRequest): Unit = {

    val (contextIDValueStr, nodeNameStr) = getContextInfo(requestPersistTask.getParams.asInstanceOf[util.Map[String, Any]])

    if (StringUtils.isNotBlank(contextIDValueStr) && StringUtils.isNotBlank(nodeNameStr)) {
      val userCreatorLabel = LabelUtil.getUserCreatorLabel(requestPersistTask.getLabels)
      val newLabels = new util.ArrayList[Label[_]]
      requestPersistTask.getLabels.filterNot(_.isInstanceOf[UserCreatorLabel]).foreach(newLabels.add)
      SerializeHelper.deserializeContextID(contextIDValueStr) match {
        case contextID: LinkisWorkflowContextID =>
          if (CSCommonUtils.CONTEXT_ENV_PROD.equalsIgnoreCase(contextID.getEnv)) {
            info(s"reset creator from ${userCreatorLabel.getCreator} to " + EntranceConfiguration.SCHEDULER_CREATOR.getValue)
            userCreatorLabel.setCreator(EntranceConfiguration.SCHEDULER_CREATOR.getValue)
          } else {
            info(s"reset creator from ${userCreatorLabel.getCreator} to " + EntranceConfiguration.FLOW_EXECUTION_CREATOR.getValue)
            userCreatorLabel.setCreator(EntranceConfiguration.FLOW_EXECUTION_CREATOR.getValue)
          }
        case _ =>
      }
      newLabels.add(userCreatorLabel)
      requestPersistTask.setLabels(newLabels)
    }
  }


  /**
    * From cs to get variable
    *
    * @param requestPersistTask
    * @return
    */
  def addCSVariable(requestPersistTask: JobRequest): Unit = {
    val variableMap = new util.HashMap[String, Any]()
    val (contextIDValueStr, nodeNameStr) = getContextInfo(requestPersistTask.getParams.asInstanceOf[util.Map[String, Any]])

    if (StringUtils.isNotBlank(contextIDValueStr)) {
      info(s"parse variable nodeName:$nodeNameStr")
      val linkisVariableList: util.List[LinkisVariable] = CSVariableService.getInstance().getUpstreamVariables(contextIDValueStr, nodeNameStr);
      if (null != linkisVariableList) {
        linkisVariableList.foreach { linkisVariable =>
          variableMap.put(linkisVariable.getKey, linkisVariable.getValue)
        }
      }
      if(variableMap.nonEmpty) {
        // 1.cs priority is low, the same ones are not added
        val varMap = TaskUtils.getVariableMap(requestPersistTask.getParams.asInstanceOf[util.Map[String, Any]])
        variableMap.foreach { keyAndValue =>
          if (! varMap.containsKey(keyAndValue._1)) {
            varMap.put(keyAndValue._1, keyAndValue._2)
          }
        }
        TaskUtils.addVariableMap(requestPersistTask.getParams.asInstanceOf[util.Map[String, Any]], varMap)
      }

      info(s"parse variable end nodeName:$nodeNameStr")
    }
  }
}