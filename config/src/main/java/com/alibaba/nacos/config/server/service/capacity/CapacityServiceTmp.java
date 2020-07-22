/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.config.server.service.capacity;

import com.alibaba.nacos.config.server.constant.CounterMode;
import com.alibaba.nacos.config.server.modules.entity.CapacityEntity;
import com.alibaba.nacos.config.server.modules.entity.GroupCapacityEntity;
import com.alibaba.nacos.config.server.modules.entity.TenantCapacityEntity;
import com.alibaba.nacos.config.server.service.PersistServiceTmp;
import com.alibaba.nacos.config.server.utils.PropertyUtil;
import com.alibaba.nacos.config.server.utils.TimeUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;

/**
 * @author Nacos
 */
@Slf4j
@Service
public class CapacityServiceTmp {

    private static final Integer ZERO = 0;

    private static final int INIT_PAGE_SIZE = 500;

    @Autowired
    private TenantCapacityPersistServiceTmp tenantCapacityPersistService;

    @Autowired
    private PersistServiceTmp persistServiceTmp;

    @Autowired
    private GroupCapacityPersistServiceTmp groupCapacityPersistServiceTmp;


    public void correctUsage() {
        correctGroupUsage();
        correctTenantUsage();
    }

    public void correctGroupUsage(String group) {
        groupCapacityPersistServiceTmp.correctUsage(group, TimeUtils.getCurrentTime());
    }

    public void correctTenantUsage(String tenant) {
        tenantCapacityPersistService.correctUsage(tenant, TimeUtils.getCurrentTime());
    }


    public void initAllCapacity() {
        initAllCapacity(false);
        initAllCapacity(true);
    }

    private void initAllCapacity(boolean isTenant) {
        int page = 0;
        while (true) {
            List<String> list;
            if (isTenant) {
                list = persistServiceTmp.getTenantIdList(page, INIT_PAGE_SIZE);
            } else {
                list = persistServiceTmp.getGroupIdList(page, INIT_PAGE_SIZE);
            }
            for (String targetId : list) {
                if (isTenant) {
                    insertTenantCapacity(targetId);
                    autoExpansion(null, targetId);
                } else {
                    insertGroupCapacity(targetId);
                    autoExpansion(targetId, null);
                }
            }
            if (list.size() < INIT_PAGE_SIZE) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignore
            }
            ++page;
        }
    }

    /**
     * 修正Group容量信息中的使用值（usage）
     */
    private void correctGroupUsage() {
        long lastId = 0;
        int pageSize = 100;
        while (true) {
            List<GroupCapacityEntity> groupCapacityList = groupCapacityPersistServiceTmp.getCapacityList4CorrectUsage(lastId,
                pageSize);
            if (groupCapacityList.isEmpty()) {
                break;
            }
            lastId = groupCapacityList.get(groupCapacityList.size() - 1).getId();
            for (GroupCapacityEntity groupCapacity : groupCapacityList) {
                String group = groupCapacity.getGroupId();
                groupCapacityPersistServiceTmp.correctUsage(group, TimeUtils.getCurrentTime());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }



    /**
     * 修正Tenant容量信息中的使用值（usage）
     */
    private void correctTenantUsage() {
        long lastId = 0;
        int pageSize = 100;
        while (true) {
            List<TenantCapacityEntity> tenantCapacityList = tenantCapacityPersistService.getCapacityList4CorrectUsage(lastId,
                pageSize);
            if (tenantCapacityList.isEmpty()) {
                break;
            }
            lastId = tenantCapacityList.get(tenantCapacityList.size() - 1).getId();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignore
            }
            for (TenantCapacityEntity tenantCapacity : tenantCapacityList) {
                String tenant = tenantCapacity.getTenantId();
                tenantCapacityPersistService.correctUsage(tenant, TimeUtils.getCurrentTime());
            }
        }
    }

    /**
     * 集群：1. 如果容量信息不存在，则初始化容量信息<br/> 2. 更新容量的使用量usage，加一或减一
     *
     * @param counterMode      增加或者减少
     * @param ignoreQuotaLimit 是否忽略容量额度限制，在关闭容量管理的限制检验功能只计数的时候为true，开启容量管理的限制检验功能则为false
     * @return 是否操作成功
     */
    public boolean insertAndUpdateClusterUsage(CounterMode counterMode, boolean ignoreQuotaLimit) {
        CapacityEntity capacity = groupCapacityPersistServiceTmp.getClusterCapacity();
        if (capacity == null) {
            insertGroupCapacity(GroupCapacityPersistService.CLUSTER);
        }
        return updateGroupUsage(counterMode, GroupCapacityPersistService.CLUSTER,
            PropertyUtil.getDefaultClusterQuota(), ignoreQuotaLimit);
    }


    public boolean updateClusterUsage(CounterMode counterMode) {
        return updateGroupUsage(counterMode, GroupCapacityPersistService.CLUSTER,
            PropertyUtil.getDefaultClusterQuota(), false);
    }

    /**
     * 提供给关闭容量管理的限制检验功能时计数使用<br> Group：1. 如果容量信息不存在，则初始化容量信息<br/> 2. 更新容量的使用量usage，加一或减一
     *
     * @param counterMode      增加或者减少
     * @param group            group
     * @param ignoreQuotaLimit 是否忽略容量额度限制，在关闭容量管理的限制检验功能只计数的时候为true，开启容量管理的限制检验功能则为false
     * @return 是否操作成功
     */
    public boolean insertAndUpdateGroupUsage(CounterMode counterMode, String group, boolean ignoreQuotaLimit) {
        GroupCapacityEntity groupCapacity = getGroupCapacity(group);
        if (groupCapacity == null) {
            initGroupCapacity(group, null, null, null, null);
        }
        return updateGroupUsage(counterMode, group, PropertyUtil.getDefaultGroupQuota(), ignoreQuotaLimit);
    }


    public GroupCapacityEntity getGroupCapacity(String group) {
        return groupCapacityPersistServiceTmp.getGroupCapacity(group);
    }


    public boolean updateGroupUsage(CounterMode counterMode, String group) {
        return updateGroupUsage(counterMode, group, PropertyUtil.getDefaultGroupQuota(), false);
    }


    /**
     * 初始化该Group的容量信息，如果到达限额，将自动扩容，以降低运维成本
     */
    private boolean initGroupCapacity(String group, Integer quota, Integer maxSize, Integer maxAggrCount,
                                      Integer maxAggrSize) {
        boolean insertSuccess = insertGroupCapacity(group, quota, maxSize, maxAggrCount, maxAggrSize);
        if (quota != null) {
            return insertSuccess;
        }
        autoExpansion(group, null);
        return insertSuccess;
    }


    /**
     * 自动扩容
     */
    private void autoExpansion(String group, String tenant) {
        CapacityEntity capacity = getCapacity(group, tenant);
        int defaultQuota = getDefaultQuota(tenant != null);
        Integer usage = capacity.getUsage();
        if (usage < defaultQuota) {
            return;
        }
        // 初始化的时候该Group/租户就已经到达限额，自动扩容，降低运维成本
        int initialExpansionPercent = PropertyUtil.getInitialExpansionPercent();
        if (initialExpansionPercent > 0) {
            int finalQuota = (int)(usage + defaultQuota * (1.0 * initialExpansionPercent / 100));
            if (tenant != null) {
                tenantCapacityPersistService.updateQuota(tenant, finalQuota);
                log.warn("[capacityManagement] 初始化的时候该租户（{}）使用量（{}）就已经到达限额{}，自动扩容到{}", tenant,
                    usage, defaultQuota, finalQuota);
            } else {
                groupCapacityPersistServiceTmp.updateQuota(group, finalQuota);
                log.warn("[capacityManagement] 初始化的时候该Group（{}）使用量（{}）就已经到达限额{}，自动扩容到{}", group,
                    usage, defaultQuota, finalQuota);
            }
        }

    }


    private int getDefaultQuota(boolean isTenant) {
        if (isTenant) {
            return PropertyUtil.getDefaultTenantQuota();
        }
        return PropertyUtil.getDefaultGroupQuota();
    }


    public CapacityEntity getCapacityWithDefault(String group, String tenant) {
        CapacityEntity capacity;
        boolean isTenant = StringUtils.isNotBlank(tenant);
        if (isTenant) {
            capacity = getTenantCapacity(tenant);
        } else {
            capacity = getGroupCapacity(group);
        }
        if (capacity == null) {
            return null;
        }
        Integer quota = capacity.getQuota();
        if (quota == 0) {
            if (isTenant) {
                capacity.setQuota(PropertyUtil.getDefaultTenantQuota());
            } else {
                if (GroupCapacityPersistService.CLUSTER.equals(group)) {
                    capacity.setQuota(PropertyUtil.getDefaultClusterQuota());
                } else {
                    capacity.setQuota(PropertyUtil.getDefaultGroupQuota());
                }
            }
        }
        Integer maxSize = capacity.getMaxSize();
        if (maxSize == 0) {
            capacity.setMaxSize(PropertyUtil.getDefaultMaxSize());
        }
        Integer maxAggrCount = capacity.getMaxAggrCount();
        if (maxAggrCount == 0) {
            capacity.setMaxAggrCount(PropertyUtil.getDefaultMaxAggrCount());
        }
        Integer maxAggrSize = capacity.getMaxAggrSize();
        if (maxAggrSize == 0) {
            capacity.setMaxAggrSize(PropertyUtil.getDefaultMaxAggrSize());
        }
        return capacity;
    }

    public boolean initCapacity(String group, String tenant) {
        if (StringUtils.isNotBlank(tenant)) {
            return initTenantCapacity(tenant);
        }
        if (GroupCapacityPersistService.CLUSTER.equals(group)) {
            return insertGroupCapacity(GroupCapacityPersistService.CLUSTER);
        }
        // Group会自动扩容
        return initGroupCapacity(group);
    }


    /**
     * 初始化该Group的容量信息，如果到达限额，将自动扩容，以降低运维成本
     */
    public boolean initGroupCapacity(String group) {
        return initGroupCapacity(group, null, null, null, null);
    }


    private boolean insertGroupCapacity(String group, Integer quota, Integer maxSize, Integer maxAggrCount,
                                        Integer maxAggrSize) {
        final Timestamp now = TimeUtils.getCurrentTime();
        GroupCapacityEntity groupCapacity = new GroupCapacityEntity();
        groupCapacity.setGroupId(group);
        // 新增时，quota=0表示限额为默认值，为了在更新默认限额时只需修改nacos配置，而不需要更新表中大部分数据
        groupCapacity.setQuota(quota == null ? ZERO : quota);
        // 新增时，maxSize=0表示大小为默认值，为了在更新默认大小时只需修改nacos配置，而不需要更新表中大部分数据
        groupCapacity.setMaxSize(maxSize == null ? ZERO : maxSize);
        groupCapacity.setMaxAggrCount(maxAggrCount == null ? ZERO : maxAggrCount);
        groupCapacity.setMaxAggrSize(maxAggrSize == null ? ZERO : maxAggrSize);
        groupCapacity.setMaxHistoryCount(0);
        groupCapacity.setGmtCreate(now);
        groupCapacity.setGmtModified(now);
        return groupCapacityPersistServiceTmp.insertGroupCapacity(groupCapacity);
    }


    public CapacityEntity getCapacity(String group, String tenant) {
        if (tenant != null) {
            return getTenantCapacity(tenant);
        }
        return getGroupCapacity(group);
    }


    private boolean updateGroupUsage(CounterMode counterMode, String group, int defaultQuota,
                                     boolean ignoreQuotaLimit) {
        final Timestamp now = TimeUtils.getCurrentTime();
        GroupCapacityEntity groupCapacity = new GroupCapacityEntity();
        groupCapacity.setGroupId(group);
        groupCapacity.setQuota(defaultQuota);
        groupCapacity.setGmtModified(now);
        if (CounterMode.INCREMENT == counterMode) {
            if (ignoreQuotaLimit) {
                return groupCapacityPersistServiceTmp.incrementUsage(groupCapacity);
            }
            // 先按默认值限额更新，大部分情况下都是默认值，默认值表里面的quota字段为0
            return groupCapacityPersistServiceTmp.incrementUsageWithDefaultQuotaLimit(groupCapacity)
                || groupCapacityPersistServiceTmp.incrementUsageWithQuotaLimit(groupCapacity);
        }
        return groupCapacityPersistServiceTmp.decrementUsage(groupCapacity);
    }


    /**
     * 提供给关闭容量管理的限制检验功能时计数使用<br/> 租户： 1. 如果容量信息不存在，则初始化容量信息<br/> 2. 更新容量的使用量usage，加一或减一
     *
     * @param counterMode      增加或者减少
     * @param tenant           租户
     * @param ignoreQuotaLimit 是否忽略容量额度限制，在关闭容量管理的限制检验功能只计数的时候为true，开启容量管理的限制检验功能则为false
     * @return 是否操作成功
     */
    public boolean insertAndUpdateTenantUsage(CounterMode counterMode, String tenant, boolean ignoreQuotaLimit) {
        TenantCapacityEntity tenantCapacity = getTenantCapacity(tenant);
        if (tenantCapacity == null) {
            // 初始化容量信息
            initTenantCapacity(tenant);
        }
        return updateTenantUsage(counterMode, tenant, ignoreQuotaLimit);
    }

    private boolean updateTenantUsage(CounterMode counterMode, String tenant, boolean ignoreQuotaLimit) {
        final Timestamp now = TimeUtils.getCurrentTime();
        TenantCapacityEntity tenantCapacity = new TenantCapacityEntity();
        tenantCapacity.setTenantId(tenant);
        tenantCapacity.setQuota(PropertyUtil.getDefaultTenantQuota());
        tenantCapacity.setGmtModified(now);
        if (CounterMode.INCREMENT == counterMode) {
            if (ignoreQuotaLimit) {
                return tenantCapacityPersistService.incrementUsage(tenantCapacity);
            }
            // 先按默认值限额更新，大部分情况下都是默认值，默认值表里面的quota字段为0
            return tenantCapacityPersistService.incrementUsageWithDefaultQuotaLimit(tenantCapacity)
                || tenantCapacityPersistService.incrementUsageWithQuotaLimit(tenantCapacity);
        }
        return tenantCapacityPersistService.decrementUsage(tenantCapacity);
    }


    public boolean updateTenantUsage(CounterMode counterMode, String tenant) {
        return updateTenantUsage(counterMode, tenant, false);
    }



    private boolean insertTenantCapacity(String tenant) {
        return insertTenantCapacity(tenant, null, null, null, null);
    }

    /**
     * 初始化该租户的容量信息，如果到达限额，将自动扩容，以降低运维成本
     */
    public boolean initTenantCapacity(String tenant, Integer quota, Integer maxSize, Integer maxAggrCount,
                                      Integer maxAggrSize) {
        boolean insertSuccess = insertTenantCapacity(tenant, quota, maxSize, maxAggrCount, maxAggrSize);
        if (quota != null) {
            return insertSuccess;
        }
        autoExpansion(null, tenant);
        return insertSuccess;
    }


    /**
     * 初始化该租户的容量信息，如果到达限额，将自动扩容，以降低运维成本
     */
    public boolean initTenantCapacity(String tenant) {
        return initTenantCapacity(tenant, null, null, null, null);
    }

    private boolean insertTenantCapacity(String tenant, Integer quota, Integer maxSize, Integer maxAggrCount,
                                         Integer maxAggrSize) {
        try {
            final Timestamp now = TimeUtils.getCurrentTime();
            TenantCapacityEntity tenantCapacity = new TenantCapacityEntity();
            tenantCapacity.setTenantId(tenant);
            // 新增时，quota=0表示限额为默认值，为了在更新默认限额时只需修改nacos配置，而不需要更新表中大部分数据
            tenantCapacity.setQuota(quota == null ? ZERO : quota);
            // 新增时，maxSize=0表示大小为默认值，为了在更新默认大小时只需修改nacos配置，而不需要更新表中大部分数据
            tenantCapacity.setMaxSize(maxSize == null ? ZERO : maxSize);
            tenantCapacity.setMaxAggrCount(maxAggrCount == null ? ZERO : maxAggrCount);
            tenantCapacity.setMaxAggrSize(maxAggrSize == null ? ZERO : maxAggrSize);
            tenantCapacity.setMaxHistoryCount(ZERO);
            tenantCapacity.setUsage(ZERO);
            tenantCapacity.setGmtCreate(now);
            tenantCapacity.setGmtModified(now);
//            return true;
            return tenantCapacityPersistService.insertTenantCapacity(tenantCapacity);
        } catch (DuplicateKeyException e) {
            // 并发情况下同时insert会出现，ignore
            log.warn("tenant: {}, message: {}", tenant, e.getMessage());
        }
        return false;
    }

    public TenantCapacityEntity getTenantCapacity(String tenant) {
        return tenantCapacityPersistService.getTenantCapacity(tenant);
    }

    private boolean insertGroupCapacity(String group) {
        return insertGroupCapacity(group, null, null, null, null);
    }


    /**
     * 提供给API接口使用<br/> 租户：记录不存在则初始化，存在则直接更新容量限额或者内容大小
     *
     * @param group   Group ID
     * @param tenant  租户
     * @param quota   容量限额
     * @param maxSize 配置内容（content）大小限制
     * @return 是否操作成功
     */
    public boolean insertOrUpdateCapacity(String group, String tenant, Integer quota, Integer maxSize, Integer
        maxAggrCount, Integer maxAggrSize) {
        if (StringUtils.isNotBlank(tenant)) {
            CapacityEntity capacity = tenantCapacityPersistService.getTenantCapacity(tenant);
            if (capacity == null) {
                return initTenantCapacity(tenant, quota, maxSize, maxAggrCount, maxAggrSize);
            }
            return tenantCapacityPersistService.updateTenantCapacity(tenant, quota, maxSize, maxAggrCount,
                maxAggrSize);
        }
        CapacityEntity capacity = groupCapacityPersistServiceTmp.getGroupCapacity(group);
        if (capacity == null) {
            return initGroupCapacity(group, quota, maxSize, maxAggrCount, maxAggrSize);
        }
        return groupCapacityPersistServiceTmp.updateGroupCapacity(group, quota, maxSize, maxAggrCount, maxAggrSize);
    }


}
