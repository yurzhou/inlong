/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.dataproxy.config.loader;

import org.apache.inlong.dataproxy.config.pojo.IdTopicConfig;

import org.apache.flume.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * 
 * ContextIdTopicConfigLoader
 */
public class ContextIdTopicConfigLoader implements IdTopicConfigLoader {

    private Context context;

    /**
     * load
     * 
     * @return
     */
    @Override
    public List<IdTopicConfig> load() {
        Map<String, String> idTopicMap = context.getSubProperties("idTopicConfig.");
        List<IdTopicConfig> configList = new ArrayList<>(idTopicMap.size());
        for (Entry<String, String> entry : idTopicMap.entrySet()) {
            IdTopicConfig config = new IdTopicConfig();
            config.setInlongGroupId(entry.getKey());
            config.setTopicName(entry.getValue());
            configList.add(config);
        }
        return configList;
    }

    /**
     * configure
     * 
     * @param context
     */
    @Override
    public void configure(Context context) {
        this.context = context;
    }

}
