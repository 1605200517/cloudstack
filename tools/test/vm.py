# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
# 
#   http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import json
import os
import random
import sys
import time
#http://localhost:8080/client/api?_=1303171692177&command=listTemplates&templatefilter=featured&zoneid=1&pagesize=6&page=1&response=json&sessionkey=%2Bh3Gh4BffWpQdk4nXmcC88uEk9k%3D
#http://localhost:8080/client/api?_=1303171711292&command=deployVirtualMachine&zoneId=1&hypervisor=KVM&templateId=4&serviceOfferingId=7&response=json&sessionkey=%2Bh3Gh4BffWpQdk4nXmcC88uEk9k%3D
#http://localhost:8080/client/api?_=1303171934824&command=queryAsyncJobResult&jobId=20&response=json&sessionkey=%2Bh3Gh4BffWpQdk4nXmcC88uEk9k%3D

class VMCreator:
    """Creates a VM """
    def __init__(self, api, params):
        self._api = api
        self._params = params
    
    def create(self):
        cmd = {'command': 'deployVirtualMachine'}
        cmd.update(self._params)
        jsonresult = self._api.GET(cmd)
        if  jsonresult is  None:
           print "Failed to create VM"
           return 0
        jsonobj = json.loads(jsonresult)
        self._jobid = jsonobj['deployvirtualmachineresponse']['jobid']
        self._vmid = jsonobj['deployvirtualmachineresponse']['id']
        print "VM %s creation is scheduled, job=%s"%(self._vmid, self._jobid)


    def poll(self, tries, wait):
        jobstatus = -1
        while jobstatus < 1 and tries > 0:
           time.sleep(wait)
           cmd = {'command': 'queryAsyncJobResult', 'jobId': self._jobid}
           jsonresult = self._api.GET(cmd)
           if  jsonresult is  None:
              print "Failed to query VM creation job"
              return -1
           jsonobj = json.loads(jsonresult)
           jobstatus = jsonobj['queryasyncjobresultresponse']['jobstatus']
           print jobstatus, type(jobstatus)
           tries = tries - 1

        if jobstatus == 1:
           jsonobj = json.loads(jsonresult)
           jobresult = jsonobj['queryasyncjobresultresponse']['jobresult']
           vm = jobresult['virtualmachine']
           print vm
        else:
          print "Failed to create vm"
      
        return jobstatus
        
