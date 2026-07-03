<template>
  <div class="log-query">
    <el-tabs v-model="activeTab" type="border-card">
      <el-tab-pane label="日志查询" name="query">
        <el-card header="日志查询">
      <el-form :model="queryForm" label-width="100px">
        <el-row :gutter="20">
          <el-col :span="24">
            <!-- DSL 配置化构建 -->
            <el-card shadow="never" class="dsl-builder-card">
              <template #header>
                <div class="dsl-builder-header">
                  <span>DSL 查询配置</span>
                  <div class="dsl-header-actions">
                    <el-button type="primary" @click="handleQuery" :loading="loading">
                      <el-icon><Search /></el-icon>
                      查询
                    </el-button>
                    <el-button @click="handleReset">重置</el-button>
                    <el-button
                      :type="showAdvanced ? 'warning' : 'default'"
                      @click="showAdvanced = !showAdvanced"
                    >
                      {{ showAdvanced ? '收起高级' : '高级查询' }}
                    </el-button>
                  </div>
                </div>
              </template>

              <!-- 高级 DSL 构建区域（默认折叠） -->
              <div v-if="showAdvanced">
                <!-- 基础配置 -->
                <el-row :gutter="16">
                  <el-col :span="6">
                    <el-form-item label="返回条数" label-width="80px">
                      <el-input-number
                        v-model="dslConfig.size"
                        :min="1"
                        :max="1000"
                        :step="10"
                        controls-position="right"
                        style="width: 100%"
                      />
                    </el-form-item>
                  </el-col>
                  <el-col :span="9">
                    <el-form-item label="时间范围" label-width="80px">
                      <el-select v-model="dslConfig.timeRange" style="width: 100%">
                        <el-option label="最近 5 分钟" value="now-5m" />
                        <el-option label="最近 15 分钟" value="now-15m" />
                        <el-option label="最近 30 分钟" value="now-30m" />
                        <el-option label="最近 1 小时" value="now-1h" />
                        <el-option label="最近 3 小时" value="now-3h" />
                        <el-option label="最近 6 小时" value="now-6h" />
                        <el-option label="最近 12 小时" value="now-12h" />
                        <el-option label="最近 24 小时" value="now-24h" />
                        <el-option label="最近 7 天" value="now-7d" />
                        <el-option label="自定义时间" value="custom" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                  <el-col :span="9" v-if="dslConfig.timeRange === 'custom'">
                    <el-form-item label="自定义" label-width="60px">
                      <el-date-picker
                        v-model="dslConfig.customStartTime"
                        type="datetime"
                        placeholder="开始时间"
                        style="width: 48%"
                      />
                      <span style="margin: 0 2px">-</span>
                      <el-date-picker
                        v-model="dslConfig.customEndTime"
                        type="datetime"
                        placeholder="结束时间"
                        style="width: 48%"
                      />
                    </el-form-item>
                  </el-col>
                </el-row>

                <!-- Must 条件 -->
                <el-divider content-position="left">Must 条件 (必须满足)</el-divider>
                <div class="condition-list">
                  <div v-for="(condition, index) in dslConfig.mustConditions" :key="'must-' + index" class="condition-item">
                    <el-select v-model="condition.field" placeholder="字段" style="width: 140px">
                      <el-option label="@timestamp" value="@timestamp" />
                      <el-option label="level" value="level" />
                      <el-option label="message" value="message" />
                      <el-option label="service" value="service" />
                      <el-option label="traceId" value="traceId" />
                      <el-option label="hostname" value="hostname" />
                      <el-option label="自定义字段" value="custom" />
                    </el-select>
                    <el-input v-if="condition.field === 'custom'" v-model="condition.customField" placeholder="字段名" style="width: 120px" />
                    <el-select v-model="condition.operator" placeholder="操作符" style="width: 110px">
                      <el-option label="range" value="range" />
                      <el-option label="match" value="match" />
                      <el-option label="match_phrase" value="match_phrase" />
                      <el-option label="term" value="term" />
                      <el-option label="wildcard" value="wildcard" />
                    </el-select>
                    <el-input v-model="condition.value" placeholder="值" style="flex: 1; min-width: 150px" />
                    <el-button type="danger" :icon="Delete" circle size="small" @click="removeCondition('must', index)" />
                  </div>
                  <el-button type="primary" size="small" @click="addCondition('must')">
                    <el-icon><Plus /></el-icon> 添加 Must 条件
                  </el-button>
                </div>

                <!-- Should 条件 -->
                <el-divider content-position="left">Should 条件 (满足任一)</el-divider>
                <div class="condition-list">
                  <div v-for="(condition, index) in dslConfig.shouldConditions" :key="'should-' + index" class="condition-item">
                    <el-select v-model="condition.field" placeholder="字段" style="width: 140px">
                      <el-option label="level" value="level" />
                      <el-option label="message" value="message" />
                      <el-option label="service" value="service" />
                      <el-option label="自定义字段" value="custom" />
                    </el-select>
                    <el-input v-if="condition.field === 'custom'" v-model="condition.customField" placeholder="字段名" style="width: 120px" />
                    <el-select v-model="condition.operator" placeholder="操作符" style="width: 130px">
                      <el-option label="match" value="match" />
                      <el-option label="match_phrase" value="match_phrase" />
                      <el-option label="term" value="term" />
                      <el-option label="wildcard" value="wildcard" />
                      <el-option label="regexp" value="regexp" />
                    </el-select>
                    <el-input v-model="condition.value" placeholder="值" style="flex: 1; min-width: 150px" />
                    <el-button type="danger" :icon="Delete" circle size="small" @click="removeCondition('should', index)" />
                  </div>
                  <el-row :gutter="10" style="margin-top: 8px">
                    <el-col :span="12">
                      <el-button type="primary" size="small" @click="addCondition('should')">
                        <el-icon><Plus /></el-icon> 添加 Should 条件
                      </el-button>
                    </el-col>
                    <el-col :span="12">
                      <el-button size="small" @click="addPresetCondition">
                        <el-icon><Plus /></el-icon> 快速添加错误模式
                      </el-button>
                    </el-col>
                  </el-row>
                </div>

                <!-- minimum_should_match -->
                <el-form-item label="至少匹配" style="margin-top: 16px">
                  <el-input-number v-model="dslConfig.minimumShouldMatch" :min="0" :max="dslConfig.shouldConditions.length || 10" />
                  <span class="form-hint">个 Should 条件</span>
                </el-form-item>

                <!-- 生成的 DSL 预览 -->
                <el-collapse v-if="generatedDsl" v-model="dslCollapseActive" style="margin-top: 16px">
                  <el-collapse-item title="生成的 DSL 查询" name="dsl">
                    <pre class="dsl-preview">{{ generatedDsl }}</pre>
                    <el-button size="small" @click="copyDsl">复制 DSL</el-button>
                  </el-collapse-item>
                </el-collapse>

                <!-- 手动输入 DSL -->
                <el-divider content-position="left">手动输入 DSL</el-divider>
                <div class="manual-dsl-section">
                  <el-input
                    v-model="manualDsl"
                    type="textarea"
                    :rows="6"
                    placeholder="在此输入自定义 DSL 查询 JSON，例如：
{
  &quot;size&quot;: 20,
  &quot;query&quot;: {
    &quot;bool&quot;: {
      &quot;must&quot;: [{ &quot;match&quot;: { &quot;level&quot;: &quot;ERROR&quot; } }],
      &quot;should&quot;: [{ &quot;match_phrase&quot;: { &quot;message&quot;: &quot;Exception&quot; } }]
    }
  }
}"
                    style="font-family: monospace"
                  />
                  <div class="manual-dsl-actions">
                    <el-button size="small" @click="formatManualDsl">格式化</el-button>
                    <el-button size="small" @click="loadManualDslToBuilder">加载到配置</el-button>
                    <el-button size="small" type="warning" @click="clearManualDsl">清空</el-button>
                  </div>
                </div>
              </div>

              <!-- 推荐查询（始终展示） -->
              <el-divider content-position="left">推荐查询</el-divider>
              <div class="recommended-queries">
                <el-card
                  v-for="(query, index) in recommendedQueries"
                  :key="index"
                  shadow="hover"
                  class="query-card"
                  @click="applyRecommendedQuery(query.dsl)"
                >
                  <div class="query-card-header">
                    <el-icon><Search /></el-icon>
                    <span>{{ query.title }}</span>
                  </div>
                  <p class="query-desc">{{ query.description }}</p>
                </el-card>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <el-card header="查询结果" class="mt-4">
      <template #header>
        <div class="result-header">
          <span>查询结果</span>
          <span class="result-count" v-if="pagination.total > 0">共 {{ pagination.total }} 条</span>
        </div>
      </template>

      <el-table :data="logs" v-loading="loading" stripe style="width: 100%">
        <el-table-column prop="level" label="级别" width="80">
          <template #default="{ row }">
            <el-tag :type="getLevelType(row.level)" size="small">{{ row.level || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="timestamp" label="时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.timestamp) }}
          </template>
        </el-table-column>
        <el-table-column prop="serviceName" label="服务" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.serviceName || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="traceId" label="TraceID" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tooltip :content="row.traceId" placement="top" v-if="row.traceId">
              <span class="trace-id">{{ row.traceId }}</span>
            </el-tooltip>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="消息" min-width="300" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="log-message">
              <el-tag v-if="row.errorType" type="danger" size="small" class="error-tag">{{ row.errorType }}</el-tag>
              <span>{{ row.message }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="hostname" label="主机" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tooltip :content="row.hostname" placement="top" v-if="row.hostname">
              <span>{{ shortText(row.hostname, 15) }}</span>
            </el-tooltip>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleAnalyze(row)">
              分析
            </el-button>
            <el-button type="info" link size="small" @click="showDetail(row)">
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="mt-4"
        v-model:current-page="pagination.page"
        v-model:page-size="pagination.pageSize"
        :total="pagination.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @size-change="handleQuery"
        @current-change="handleQuery"
      />
        </el-card>
      </el-tab-pane>

      <!-- 定时任务配置页签 -->
      <el-tab-pane label="定时任务配置" name="config">
        <el-card header="日志拉取配置">
          <el-alert type="info" :closable="false" style="margin-bottom: 16px">
            配置定时任务自动拉取指定应用的错误日志，系统将按设定间隔自动执行日志查询并入库分析。
          </el-alert>

          <el-button type="primary" @click="showAddConfigDialog" style="margin-bottom: 16px">
            <el-icon><Plus /></el-icon> 新增配置
          </el-button>

          <el-table :data="configs" v-loading="configLoading" stripe>
            <el-table-column prop="appId" label="应用ID" width="120" />
            <el-table-column prop="projectPath" label="项目路径" min-width="200" show-overflow-tooltip />
            <el-table-column prop="pullIntervalMinutes" label="拉取间隔(分钟)" width="120" />
            <el-table-column prop="enabled" label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">
                  {{ row.enabled ? '启用' : '禁用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="lastPullAt" label="上次拉取" width="180">
              <template #default="{ row }">
                {{ row.lastPullAt ? formatConfigTime(row.lastPullAt) : '-' }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200">
              <template #default="{ row }">
                <el-button type="success" link size="small" @click="viewConfigReports(row)">查看报告</el-button>
                <el-button type="primary" link size="small" @click="editConfig(row)">编辑</el-button>
                <el-button
                  :type="row.enabled ? 'warning' : 'success'"
                  link
                  size="small"
                  @click="toggleConfigStatus(row)"
                >
                  {{ row.enabled ? '禁用' : '启用' }}
                </el-button>
                <el-button type="danger" link size="small" @click="deleteConfig(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 分析报告页签 -->
      <el-tab-pane label="分析报告" name="reports">
        <el-card header="AI 分析报告">
          <el-alert type="info" :closable="false" style="margin-bottom: 16px">
            查看已提交的日志分析任务及其 AI 生成的根因分析报告。
          </el-alert>

          <el-form :inline="true" style="margin-bottom: 16px">
            <el-form-item label="状态">
              <el-select v-model="reportFilter.status" clearable placeholder="全部" style="width: 120px">
                <el-option label="待处理" value="pending" />
                <el-option label="处理中" value="processing" />
                <el-option label="已完成" value="completed" />
                <el-option label="失败" value="failed" />
              </el-select>
            </el-form-item>
            <el-form-item label="时间范围">
              <el-date-picker
                v-model="reportFilter.startTime"
                type="datetime"
                placeholder="开始时间"
                style="width: 180px"
              />
              <span style="margin: 0 4px">-</span>
              <el-date-picker
                v-model="reportFilter.endTime"
                type="datetime"
                placeholder="结束时间"
                style="width: 180px"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadReports" :loading="reportsLoading">
                <el-icon><Search /></el-icon> 查询
              </el-button>
              <el-button type="success" :loading="exportingZip" @click="handleExportZip">
                <el-icon><Download /></el-icon> 批量导出 ZIP
              </el-button>
            </el-form-item>
          </el-form>

          <el-table :data="reports" v-loading="reportsLoading" stripe>
            <el-table-column prop="reportId" label="报告ID" width="150" />
            <el-table-column prop="appId" label="应用" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.appId" type="info" size="small">{{ row.appId }}</el-tag>
                <span v-else style="color: #909399">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="160">
              <template #default="{ row }">
                <!-- L-11: pending/processing 状态显示进度条 -->
                <div v-if="row.status === 'pending' || row.status === 'processing'" class="progress-cell">
                  <el-progress
                    :percentage="reportProgressMap.get(row.reportId)?.progress || 0"
                    :stroke-width="6"
                    :show-text="false"
                    :status="row.status === 'processing' ? '' : 'warning'"
                  />
                  <div class="progress-info">
                    <span class="progress-percent">{{ reportProgressMap.get(row.reportId)?.progress || 0 }}%</span>
                    <span class="progress-stage" v-if="reportProgressMap.get(row.reportId)?.stage">
                      {{ reportProgressMap.get(row.reportId)?.stage }}
                    </span>
                  </div>
                </div>
                <!-- 已完成/失败状态显示静态 tag -->
                <el-tag v-else :type="getReportStatusType(row.status)">{{ getReportStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="errorType" label="错误类型" width="180">
              <template #default="{ row }">
                <span v-if="row.errorType">{{ row.errorType }}</span>
                <span v-else style="color: #909399">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="serviceName" label="服务" width="150">
              <template #default="{ row }">
                <span v-if="row.serviceName">{{ row.serviceName }}</span>
                <span v-else style="color: #909399">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="occurrenceCount" label="出现次数" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.occurrenceCount && row.occurrenceCount > 1" type="warning" size="small">
                  {{ row.occurrenceCount }}
                </el-tag>
                <span v-else>1</span>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" width="180">
              <template #default="{ row }">
                {{ formatReportTime(row.createdAt) }}
              </template>
            </el-table-column>
            <el-table-column prop="updatedAt" label="更新时间" width="180">
              <template #default="{ row }">
                {{ formatReportTime(row.updatedAt) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="280">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="viewReportDetail(row)" :disabled="row.status !== 'completed'">
                  查看报告
                </el-button>
                <el-button type="warning" link size="small" @click="handleReanalyzeReport(row)" :disabled="row.status === 'pending' || row.status === 'processing'" :loading="row.reanalyzing">
                  重新分析
                </el-button>
                <el-button type="danger" link size="small" @click="handleDeleteReport(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            class="mt-4"
            v-model:current-page="reportsPagination.page"
            v-model:page-size="reportsPagination.pageSize"
            :total="reportsPagination.total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @size-change="loadReports"
            @current-change="loadReports"
          />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 新增/编辑配置弹窗 -->
    <el-dialog v-model="configDialogVisible" :title="editingConfig ? '编辑配置' : '新增配置'" width="600px">
      <el-form :model="configForm" label-width="120px">
        <!-- Task 72: 按分组快速选择 appId -->
        <el-form-item label="按分组选择" v-if="groups.length > 0">
          <el-select
            v-model="selectedGroupId"
            clearable
            filterable
            placeholder="选择分组自动加载 appId 和项目路径"
            style="width: 100%"
            @change="onGroupChange"
          >
            <el-option
              v-for="group in groups"
              :key="group.appId"
              :label="`${group.appName} (${group.appId})`"
              :value="group.appId"
            >
              <div style="display: flex; justify-content: space-between; align-items: center">
                <span>{{ group.appName }}</span>
                <el-tag size="small" type="info">{{ group.projectPaths.length }}个项目</el-tag>
              </div>
            </el-option>
          </el-select>
          <div class="group-hint" v-if="selectedGroupId">
            <el-icon :size="12"><Check /></el-icon>
            <span>选择分组后将自动填充 appId 和项目路径</span>
          </div>
        </el-form-item>
        <el-form-item label="应用ID" required>
          <el-input v-model="configForm.appId" placeholder="如: hiapm" :disabled="editingConfig" />
        </el-form-item>
        <el-form-item label="图谱化项目" required>
          <el-select
            v-model="configForm.projectPaths"
            multiple
            filterable
            placeholder="选择已图谱化的项目（可多选）"
            :loading="graphedProjectsLoading"
            style="width: 100%"
          >
            <el-option
              v-for="path in graphedProjects"
              :key="path"
              :label="path"
              :value="path"
            />
          </el-select>
          <div class="form-hint" v-if="graphedProjects.length === 0 && !graphedProjectsLoading">
            <el-icon><Warning /></el-icon>
            无已图谱化项目，请先在「知识图谱管理」页面生成图谱
          </div>
        </el-form-item>
        <el-form-item label="DSL查询">
          <el-input
            v-model="configForm.dslQuery"
            type="textarea"
            :rows="6"
            placeholder="DSL查询语句(JSON格式)"
          />
        </el-form-item>
        <el-form-item label="拉取间隔(分钟)">
          <el-input-number v-model="configForm.pullIntervalMinutes" :min="1" :max="60" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="configForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveConfig" :loading="configSaving">保存</el-button>
      </template>
    </el-dialog>

    <!-- 日志详情弹窗 -->
    <el-dialog v-model="detailVisible" title="日志详情" width="800px" append-to-body>
      <div class="log-detail" v-if="selectedLog">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="时间">{{ selectedLog.timestamp }}</el-descriptions-item>
          <el-descriptions-item label="级别">
            <el-tag :type="getLevelType(selectedLog.level)">{{ selectedLog.level }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="服务">{{ selectedLog.serviceName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="TraceID">{{ selectedLog.traceId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="主机">{{ selectedLog.hostname || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Pod">{{ selectedLog.podName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="命名空间">{{ selectedLog.namespace || '-' }}</el-descriptions-item>
          <el-descriptions-item label="错误类型">
            <el-tag v-if="parsedLogDetail?.errorType" type="danger">{{ parsedLogDetail.errorType }}</el-tag>
            <span v-else>{{ selectedLog.errorType || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="日志源" :span="2">{{ selectedLog.logSource || '-' }}</el-descriptions-item>
        </el-descriptions>

        <!-- 解析后的错误信息 -->
        <div class="detail-section" v-if="parsedLogDetail?.parseSuccess">
          <div class="section-title error-title">
            <el-icon color="#f56c6c"><Warning /></el-icon>
            错误信息
          </div>
          <div class="error-info-card">
            <div class="error-type-row" v-if="parsedLogDetail.errorType">
              <span class="label">异常类型:</span>
              <el-tag type="danger" effect="dark">{{ parsedLogDetail.errorType }}</el-tag>
            </div>
            <div class="error-msg-row" v-if="parsedLogDetail.errorMessage">
              <span class="label">错误描述:</span>
              <code class="error-message">{{ parsedLogDetail.errorMessage }}</code>
            </div>
            <div class="error-header-row" v-if="parsedLogDetail.headerMessage && parsedLogDetail.headerMessage !== parsedLogDetail.errorMessage">
              <span class="label">日志上下文:</span>
              <span class="header-message">{{ parsedLogDetail.headerMessage }}</span>
            </div>
          </div>
        </div>

        <!-- 结构化堆栈信息 -->
        <div class="detail-section" v-if="parsedLogDetail?.stackFrames?.length">
          <div class="section-title">
            <el-icon><Document /></el-icon>
            调用栈 ({{ parsedLogDetail.stackFrames.length }} 帧)
          </div>
          <div class="stack-frames">
            <div
              v-for="(frame, index) in parsedLogDetail.stackFrames"
              :key="index"
              class="stack-frame"
              :class="{ 'frame-project': !isFrameworkFrame(frame.className) }"
            >
              <span class="frame-index">{{ index + 1 }}</span>
              <span class="frame-class">{{ frame.className }}</span>
              <span class="frame-method">.{{ frame.methodName }}</span>
              <span class="frame-location">({{ frame.fileName }}:{{ frame.lineNumber || 'N/A' }})</span>
            </div>
          </div>
        </div>

        <!-- Caused by 链 -->
        <div class="detail-section" v-if="parsedLogDetail?.causedByChain?.length">
          <div class="section-title caused-by-title">
            <el-icon color="#e6a23c"><Cpu /></el-icon>
            Caused By ({{ parsedLogDetail.causedByChain.length }} 层)
          </div>
          <div class="caused-by-chain">
            <div v-for="(cause, index) in parsedLogDetail.causedByChain" :key="index" class="caused-by-item">
              <div class="cause-header">
                <el-tag type="warning" size="small">{{ cause.errorType }}</el-tag>
                <code class="cause-message">{{ cause.errorMessage }}</code>
              </div>
              <div class="cause-frames" v-if="cause.stackFrames.length">
                <div v-for="(frame, fIndex) in cause.stackFrames.slice(0, 3)" :key="fIndex" class="stack-frame compact">
                  <span class="frame-class">{{ frame.className }}</span>
                  <span class="frame-method">.{{ frame.methodName }}</span>
                </div>
                <div v-if="cause.stackFrames.length > 3" class="more-frames">
                  ... {{ cause.stackFrames.length - 3 }} more
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 原始日志消息 -->
        <div class="detail-section" v-if="selectedLog.message">
          <el-collapse>
            <el-collapse-item title="查看原始日志">
              <pre class="message-content raw-log">{{ selectedLog.message }}</pre>
            </el-collapse-item>
          </el-collapse>
        </div>

        <div class="detail-section" v-if="selectedLog.rawFields">
          <div class="section-title">原始字段</div>
          <el-collapse>
            <el-collapse-item title="点击展开查看所有字段">
              <pre class="raw-fields">{{ JSON.stringify(selectedLog.rawFields, null, 2) }}</pre>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>
    </el-dialog>

    <!-- 报告详情弹窗 -->
    <el-dialog v-model="reportDetailVisible" title="分析报告详情" width="800px">
      <div class="report-detail" v-if="selectedReport">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="报告ID">{{ selectedReport.reportId }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="getReportStatusType(selectedReport.status)">{{ getReportStatusText(selectedReport.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="错误类型">
            <el-tag v-if="selectedReport.errorType" type="danger">{{ selectedReport.errorType }}</el-tag>
            <span v-else style="color: #909399">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="服务">
            <span v-if="selectedReport.serviceName">{{ selectedReport.serviceName }}</span>
            <span v-else style="color: #909399">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatReportTime(selectedReport.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatReportTime(selectedReport.updatedAt) }}</el-descriptions-item>
          <el-descriptions-item label="出现次数" v-if="selectedReport.occurrenceCount && selectedReport.occurrenceCount > 1">
            <el-tag type="warning">{{ selectedReport.occurrenceCount }} 次合并</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 错误摘要 -->
        <div class="detail-section" v-if="selectedReport.errorSummary">
          <div class="section-title">
            <el-icon><Warning /></el-icon>
            错误摘要
          </div>
          <div class="markdown-content error-summary-md" v-html="renderMarkdown(selectedReport.errorSummary)"></div>
        </div>

        <!-- 根因分析 -->
        <div class="detail-section" v-if="selectedReport.rootCause">
          <div class="section-title">
            <el-icon><Cpu /></el-icon>
            根因分析
          </div>
          <div class="markdown-content root-cause-md" v-html="renderMarkdown(selectedReport.rootCause)"></div>
        </div>

        <!-- 修复建议 -->
        <div class="detail-section" v-if="selectedReport.fixSuggestions">
          <div class="section-title">
            <el-icon><Check /></el-icon>
            修复建议
          </div>
          <div class="markdown-content fix-suggestions-md" v-html="renderMarkdown(selectedReport.fixSuggestions)"></div>
        </div>

        <!-- 代码片段 -->
        <div class="detail-section" v-if="selectedReport.codeSnippets">
          <div class="section-title">
            <el-icon><Document /></el-icon>
            相关代码
          </div>
          <div class="markdown-content code-snippets-md" v-html="renderMarkdown(selectedReport.codeSnippets)"></div>
        </div>
      </div>
      <template #footer>
        <el-button @click="reportDetailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search, Document, Warning, Cpu, Check, Delete, Plus, Download } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { logAnalysisApi, type AppLogConfig } from '@/api/logAnalysis'
import { aiAnalysisApi } from '@/api/aiAnalysis'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { projectGroupApi, type ProjectGroup } from '@/api/projectGroup'
import type { LogEntry } from '@/types/log'
import { parseJavaErrorLog, formatForAnalysis, type ParsedErrorLog } from '@/utils/logParser'
import { renderMarkdown } from '@/utils/markdown'
import { downloadBlob } from '@/utils/download'

const router = useRouter()
const workspaceStore = useWorkspaceStore()

const loading = ref(false)
const logs = ref<LogEntry[]>([])
const detailVisible = ref(false)
const selectedLog = ref<LogEntry | null>(null)

// ========== Tab 状态 ==========
const activeTab = ref('query')

// ========== 配置管理 ==========
const configLoading = ref(false)
const configs = ref<AppLogConfig[]>([])
const configDialogVisible = ref(false)
const editingConfig = ref<AppLogConfig | null>(null)
const configSaving = ref(false)
const graphedProjects = ref<string[]>([])
const graphedProjectsLoading = ref(false)
const configForm = reactive({
  appId: '',
  projectPaths: [] as string[],  // 多选数组
  dslQuery: '',
  pullIntervalMinutes: 10,
  enabled: true
})

// Task 72: 项目分组快速选择
const groups = ref<ProjectGroup[]>([])
const selectedGroupId = ref<string>('')
const loadingGroups = ref<boolean>(false)

const loadGroups = async () => {
  loadingGroups.value = true
  try {
    groups.value = await projectGroupApi.getGroups()
  } catch {
    groups.value = []
  } finally {
    loadingGroups.value = false
  }
}

const onGroupChange = (appId: string) => {
  if (!appId) {
    selectedGroupId.value = ''
    return
  }
  const group = groups.value.find(g => g.appId === appId)
  if (group && group.projectPaths.length > 0) {
    configForm.appId = appId
    configForm.projectPaths = [...group.projectPaths]
    ElMessage.success(`已加载分组 "${group.appName}" 下的 ${group.projectPaths.length} 个项目`)
  }
}

const loadGraphedProjects = async () => {
  graphedProjectsLoading.value = true
  try {
    const res = await logAnalysisApi.getGraphedProjects()
    graphedProjects.value = res || []
  } catch (e: any) {
    // Neo4j 未配置时静默失败
    graphedProjects.value = []
  } finally {
    graphedProjectsLoading.value = false
  }
}

const loadConfigs = async () => {
  configLoading.value = true
  try {
    const res = await logAnalysisApi.getConfigs()
    configs.value = res || []
  } catch (e: any) {
    ElMessage.error('加载配置失败: ' + e.message)
  } finally {
    configLoading.value = false
  }
}

const showAddConfigDialog = () => {
  editingConfig.value = null
  selectedGroupId.value = ''
  Object.assign(configForm, {
    appId: '',
    projectPaths: [],
    dslQuery: '',
    pullIntervalMinutes: 10,
    enabled: true
  })
  loadGraphedProjects()  // 加载已图谱化项目列表
  loadGroups()  // Task 72: 加载项目分组列表
  configDialogVisible.value = true
}

const editConfig = (config: AppLogConfig) => {
  editingConfig.value = config
  // 将存储的逗号分隔字符串转为数组
  const projectPaths = config.projectPath ? config.projectPath.split(',').filter(p => p.trim()) : []
  selectedGroupId.value = config.appId  // 如果有对应分组，选中它
  Object.assign(configForm, {
    appId: config.appId,
    projectPaths,
    dslQuery: config.dslQuery,
    pullIntervalMinutes: config.pullIntervalMinutes,
    enabled: config.enabled
  })
  loadGraphedProjects()
  loadGroups()  // Task 72: 加载项目分组列表
  configDialogVisible.value = true
}

const saveConfig = async () => {
  if (!configForm.appId) {
    ElMessage.warning('请填写应用ID')
    return
  }
  if (configForm.projectPaths.length === 0) {
    ElMessage.warning('请选择至少一个图谱化项目')
    return
  }
  configSaving.value = true
  try {
    // 将多选数组转为逗号分隔字符串存储
    const saveData: AppLogConfig = {
      appId: configForm.appId,
      projectPath: configForm.projectPaths.join(','),
      dslQuery: configForm.dslQuery,
      pullIntervalMinutes: configForm.pullIntervalMinutes,
      enabled: configForm.enabled
    }
    await logAnalysisApi.saveConfig(saveData)
    ElMessage.success('配置保存成功')
    configDialogVisible.value = false
    loadConfigs()
  } catch (e: any) {
    ElMessage.error('保存失败: ' + e.message)
  } finally {
    configSaving.value = false
  }
}

const toggleConfigStatus = async (config: AppLogConfig) => {
  try {
    await logAnalysisApi.toggleConfig(config.appId)
    ElMessage.success('状态已更新')
    loadConfigs()
  } catch (e: any) {
    ElMessage.error('操作失败: ' + e.message)
  }
}

const deleteConfig = async (config: AppLogConfig) => {
  try {
    await ElMessageBox.confirm(`确认删除配置 "${config.appId}"?`, '提示', { type: 'warning' })
    await logAnalysisApi.deleteConfig(config.appId)
    ElMessage.success('已删除')
    loadConfigs()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败: ' + e.message)
    }
  }
}

// Task 71: 查看指定配置的报告列表
const viewConfigReports = async (config: AppLogConfig) => {
  activeTab.value = 'reports'
  reportsLoading.value = true
  try {
    const res = await logAnalysisApi.getReportsByAppId(config.appId)
    reports.value = res?.list || []
    reportsPagination.total = res?.total || 0
    if (reports.value.length === 0) {
      ElMessage.info(`应用 "${config.appId}" 暂无分析报告`)
    } else {
      ElMessage.success(`已加载 ${reportsPagination.total} 条报告`)
    }
  } catch (e: any) {
    ElMessage.error('加载报告失败: ' + e.message)
  } finally {
    reportsLoading.value = false
  }
}

const formatConfigTime = (timestamp: number) => {
  return new Date(timestamp * 1000).toLocaleString('zh-CN')
}

// ========== 分析报告管理 ==========
const reportsLoading = ref(false)
const reports = ref<any[]>([])
const reportFilter = reactive({
  status: '' ,
  startTime: null as Date | null, 
  endTime: null as Date | null
})
const reportsPagination = reactive({
  page: 1,
  pageSize: 10,
  total: 0
})
const reportDetailVisible = ref(false)
const exportingZip = ref(false)
const selectedReport = ref<any | null>(null)

// L-11: 分析进度反馈
interface ReportProgress {
  progress: number
  stage: string
  etaSeconds?: number
}
const reportProgressMap = ref<Map<string, ReportProgress>>(new Map())
const pollingTimers = new Map<string, number>()

const loadReports = async () => {
  reportsLoading.value = true
  try {
    const params: any = {
      page: reportsPagination.page,
      pageSize: reportsPagination.pageSize
    }
    if (reportFilter.status) {
      params.status = reportFilter.status
    }
    const res = await logAnalysisApi.getReports(params)
    reports.value = res?.list || []
    reportsPagination.total = res?.total || 0

    // L-11: 对 pending/processing 状态的报告启动状态轮询
    startProgressPolling()
  } catch (e: any) {
    ElMessage.error('加载报告失败: ' + e.message)
  } finally {
    reportsLoading.value = false
  }
}

// L-11: 启动进度轮询
const startProgressPolling = () => {
  // 清理已完成的报告轮询
  for (const report of reports.value) {
    if (report.status === 'completed' || report.status === 'failed') {
      stopPolling(report.reportId)
      reportProgressMap.value.delete(report.reportId)
    }
  }

  // 只对 pending/processing 状态的报告启动轮询，最多同时轮询 3 个
  const toPoll = reports.value
    .filter(r => r.status === 'pending' || r.status === 'processing')
    .slice(0, 3)

  for (const report of toPoll) {
    const reportId = report.reportId
    if (!pollingTimers.has(reportId)) {
      // 立即获取一次状态
      pollReportStatus(reportId)
      // 启动轮询
      const timer = window.setInterval(() => pollReportStatus(reportId), 3000)
      pollingTimers.set(reportId, timer)
    }
  }
}

// L-11: 轮询单个报告状态
const pollReportStatus = async (reportId: string) => {
  try {
    const res = await logAnalysisApi.getStatus(reportId)
    const status = res?.status
    const progress = res?.progress || 0
    const stage = res?.stage || ''
    const etaSeconds = res?.etaSeconds

    // 更新进度状态
    reportProgressMap.value.set(reportId, { progress, stage, etaSeconds })

    // 状态变为 completed 或 failed 时停止轮询并刷新列表
    if (status === 'completed' || status === 'failed') {
      stopPolling(reportId)
      // 延迟刷新列表，避免频繁刷新
      setTimeout(() => loadReports(), 1000)
    }
  } catch (e: any) {
    // 轮询失败时静默处理，不中断其他轮询
    console.warn(`Poll status failed for ${reportId}:`, e.message)
  }
}

// L-11: 停止轮询
const stopPolling = (reportId: string) => {
  const timer = pollingTimers.get(reportId)
  if (timer) {
    clearInterval(timer)
    pollingTimers.delete(reportId)
  }
}

// L-11: 清理所有轮询定时器
const clearAllPollingTimers = () => {
  for (const timer of pollingTimers.values()) {
    clearInterval(timer)
  }
  pollingTimers.clear()
}

const viewReportDetail = async (report: any) => {
  try {
    const res = await logAnalysisApi.getReport(report.reportId)
    selectedReport.value = res
    reportDetailVisible.value = true
  } catch (e: any) {
    ElMessage.error('获取报告详情失败: ' + e.message)
  }
}

// checkReportStatus removed as it was never referenced in the template

const handleReanalyzeReport = async (report: any) => {
  try {
    await ElMessageBox.confirm(
      `确认重新分析报告 "${report.reportId}"？这将重新执行完整的 DAG 分析流程。`,
      '重新分析',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    )

    // Set reanalyzing state
    report.reanalyzing = true
    await logAnalysisApi.reanalyze(report.reportId)
    ElMessage.success('已触发重新分析，请稍后刷新查看结果')

    // Refresh reports list after 3 seconds
    setTimeout(loadReports, 3000)
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('触发重新分析失败: ' + e.message)
    }
  } finally {
    report.reanalyzing = false
  }
}

const handleDeleteReport = async (report: any) => {
  try {
    await ElMessageBox.confirm(
      `确认删除报告 "${report.reportId}"？此操作不可恢复。`,
      '删除报告',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )

    await logAnalysisApi.deleteReport(report.reportId)
    ElMessage.success('报告已删除')
    loadReports()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('删除失败: ' + e.message)
    }
  }
}

const handleExportZip = async () => {
  exportingZip.value = true
  try {
    const startTime = reportFilter.startTime ? reportFilter.startTime.toISOString() : undefined
    const endTime = reportFilter.endTime ? reportFilter.endTime.toISOString() : undefined
    const blob = await logAnalysisApi.exportReportsZip(startTime, endTime)
    const timestamp = new Date().toISOString().slice(0, 19).replace(/[:-]/g, '' )
    const filename = `log-reports-${timestamp}.zip`
    downloadBlob(blob, filename)
    ElMessage.success('报告已批量导出' )
  } catch (e: any) {
    ElMessage.error('导出失败: ' + (e.message || '请稍后重试' ))
  } finally {
    exportingZip.value = false
  }
}


const getReportStatusType = (status: string): string => {
  switch (status) {
    case 'completed': return 'success'
    case 'failed': return 'danger'
    case 'processing': return 'warning'
    case 'pending': return 'info'
    default: return 'info'
  }
}

const getReportStatusText = (status: string): string => {
  switch (status) {
    case 'completed': return '已完成'
    case 'failed': return '失败'
    case 'processing': return '处理中'
    case 'pending': return '待处理'
    default: return status
  }
}


const formatReportTime = (time: any): string => {
  if (!time) return '-'
  if (typeof time === 'string') return time.replace('T', ' ').substring(0, 19)
  return new Date(time).toLocaleString('zh-CN')
}

onMounted(() => {
  // activeTab defaults to 'query', config/reports loaded on tab switch via watch
})

// L-11: 组件卸载时清理所有定时器
onUnmounted(() => {
  clearAllPollingTimers()
})

watch(activeTab, (tab) => {
  if (tab === 'config') {
    loadConfigs()
  }
  if (tab === 'reports') {
    loadReports()
  }
})

// 解析选中日志的详情
const parsedLogDetail = computed<ParsedErrorLog | null>(() => {
  if (!selectedLog.value) return null
  const rawLog = selectedLog.value.message || selectedLog.value.stackTrace || ''
  if (!rawLog) return null
  return parseJavaErrorLog(rawLog)
})

// 判断是否为框架代码
const isFrameworkFrame = (className: string): boolean => {
  const frameworkPrefixes = [
    'java.',
    'javax.',
    'sun.',
    'org.springframework.',
    'org.apache.',
    'com.zaxxer.',
    'io.undertow.',
    'org.jboss.',
    'org.eclipse.',
    'ch.qos.logback.',
    'org.slf4j.'
  ]
  return frameworkPrefixes.some(prefix => className.startsWith(prefix))
}

// DSL 配置化
interface DslCondition {
  field: string
  customField: string
  operator: string
  value: string
}

const dslConfig = reactive({
  size: 20,
  timeRange: 'now-15m',
  customStartTime: null as Date | null,
  customEndTime: null as Date | null,
  mustConditions: [] as DslCondition[],
  shouldConditions: [
    { field: 'message', customField: '', operator: 'match_phrase', value: 'Caused by:' },
    { field: 'message', customField: '', operator: 'match_phrase', value: '*ExceptionHandler' },
    { field: 'level', customField: '', operator: 'match', value: 'ERROR' },
    { field: 'level', customField: '', operator: 'match', value: 'SEVERE' },
    { field: 'message', customField: '', operator: 'match_phrase', value: '.*Exception' },
    { field: 'message', customField: '', operator: 'match_phrase', value: '.*Error' },
    { field: 'message', customField: '', operator: 'match_phrase', value: 'at org.springframework' }
  ] as DslCondition[],
  minimumShouldMatch: 1
})

const generatedDsl = ref('')
const dslCollapseActive = ref(['dsl'])
const manualDsl = ref('')

// 高级查询区域显隐控制
const showAdvanced = ref(false)

// 推荐查询
const recommendedQueries = [
  {
    title: '错误日志查询',
    description: '查询最近 15 分钟的所有错误日志',
    dsl: `{
  "size": 20,
  "query": {
    "bool": {
      "must": [
        { "range": { "@timestamp": { "gte": "now-15m" } } }
      ],
      "should": [
        { "match_phrase": { "message": "Caused by:" } },
        { "match_phrase": { "message": "*ExceptionHandler" } },
        { "match": { "level": "ERROR" } },
        { "match": { "level": "SEVERE" } },
        { "match_phrase": { "message": ".*Exception" } },
        { "match_phrase": { "message": ".*Error" } },
        { "match_phrase": { "message": "at org.springframework" } }
      ],
      "minimum_should_match": 1
    }
  }
}`
  },
  {
    title: 'NullPointerException',
    description: '查询空指针异常日志',
    dsl: `{
  "size": 20,
  "query": {
    "bool": {
      "must": [
        { "range": { "@timestamp": { "gte": "now-1h" } } },
        { "match_phrase": { "message": "NullPointerException" } }
      ]
    }
  }
}`
  },
  {
    title: '数据库异常',
    description: '查询数据库相关错误',
    dsl: `{
  "size": 20,
  "query": {
    "bool": {
      "must": [
        { "range": { "@timestamp": { "gte": "now-1h" } } }
      ],
      "should": [
        { "match_phrase": { "message": "SQLException" } },
        { "match_phrase": { "message": "DataAccessException" } },
        { "match_phrase": { "message": "Connection refused" } },
        { "match_phrase": { "message": "Timeout" } }
      ],
      "minimum_should_match": 1
    }
  }
}`
  },
  {
    title: 'Spring 异常',
    description: '查询 Spring 框架相关错误',
    dsl: `{
  "size": 20,
  "query": {
    "bool": {
      "must": [
        { "range": { "@timestamp": { "gte": "now-1h" } } }
      ],
      "should": [
        { "match_phrase": { "message": "at org.springframework" } },
        { "match_phrase": { "message": "BeanCreationException" } },
        { "match_phrase": { "message": "NoSuchBeanDefinitionException" } },
        { "match_phrase": { "message": "HttpMessageNotReadableException" } }
      ],
      "minimum_should_match": 1
    }
  }
}`
  }
]

// 应用推荐查询
const applyRecommendedQuery = (dsl: string) => {
  manualDsl.value = dsl
  ElMessage.success('已加载推荐查询，点击"查询"按钮执行')
}

// 生成 DSL JSON
const buildDslQuery = () => {
  const query: any = {
    size: dslConfig.size,
    query: {
      bool: {} as any
    }
  }

  // Must 条件
  if (dslConfig.mustConditions.length > 0) {
    query.query.bool.must = dslConfig.mustConditions.map(cond => {
      const fieldName = cond.field === 'custom' ? cond.customField : cond.field
      if (cond.operator === 'range') {
        return { range: { [fieldName]: { gte: cond.value } } }
      }
      return { [cond.operator]: { [fieldName]: cond.value } }
    })
  }

  // 时间范围 (添加到 must)
  if (!query.query.bool.must) {
    query.query.bool.must = []
  }
  if (dslConfig.timeRange === 'custom' && dslConfig.customStartTime) {
    query.query.bool.must.push({
      range: {
        '@timestamp': {
          gte: dslConfig.customStartTime.toISOString(),
          lte: (dslConfig.customEndTime || new Date()).toISOString()
        }
      }
    })
  } else if (dslConfig.timeRange !== 'custom') {
    query.query.bool.must.push({
      range: {
        '@timestamp': { gte: dslConfig.timeRange }
      }
    })
  }

  // Should 条件
  if (dslConfig.shouldConditions.length > 0) {
    query.query.bool.should = dslConfig.shouldConditions.map(cond => {
      const fieldName = cond.field === 'custom' ? cond.customField : cond.field
      return { [cond.operator]: { [fieldName]: cond.value } }
    })
    query.query.bool.minimum_should_match = dslConfig.minimumShouldMatch
  }

  return query
}

// 监听 dslConfig 变化，实时更新 DSL 预览
watch(
  () => dslConfig,
  () => {
    // 只有在非手动 DSL 模式下才自动更新预览
    if (!manualDsl.value.trim()) {
      const dsl = buildDslQuery()
      generatedDsl.value = JSON.stringify(dsl, null, 2)
    }
  },
  { deep: true }
)

// 初始化时生成 DSL 预览
const initDsl = buildDslQuery()
generatedDsl.value = JSON.stringify(initDsl, null, 2)

const previewDsl = () => {
  const dsl = buildDslQuery()
  generatedDsl.value = JSON.stringify(dsl, null, 2)
  dslCollapseActive.value = ['dsl'] // 展开折叠面板
}

const copyDsl = () => {
  navigator.clipboard.writeText(generatedDsl.value)
  ElMessage.success('DSL 已复制到剪贴板')
}

const addCondition = (type: 'must' | 'should') => {
  const newCondition: DslCondition = {
    field: 'message',
    customField: '',
    operator: 'match',
    value: ''
  }
  if (type === 'must') {
    dslConfig.mustConditions.push(newCondition)
  } else {
    dslConfig.shouldConditions.push(newCondition)
  }
}

const removeCondition = (type: 'must' | 'should', index: number) => {
  if (type === 'must') {
    dslConfig.mustConditions.splice(index, 1)
  } else {
    dslConfig.shouldConditions.splice(index, 1)
  }
}

const addPresetCondition = () => {
  const presets = [
    { field: 'message', customField: '', operator: 'match_phrase', value: 'Caused by:' },
    { field: 'message', customField: '', operator: 'match_phrase', value: 'Exception' },
    { field: 'level', customField: '', operator: 'match', value: 'ERROR' }
  ]
  const randomPreset = presets[Math.floor(Math.random() * presets.length)]
  dslConfig.shouldConditions.push({ ...randomPreset })
}

// 手动 DSL 相关方法
const formatManualDsl = () => {
  if (!manualDsl.value.trim()) return
  try {
    const parsed = JSON.parse(manualDsl.value)
    manualDsl.value = JSON.stringify(parsed, null, 2)
    ElMessage.success('DSL 格式化成功')
  } catch (e: any) {
    ElMessage.error(`JSON 格式错误: ${e.message}`)
  }
}

const loadManualDslToBuilder = () => {
  if (!manualDsl.value.trim()) return
  try {
    const parsed = JSON.parse(manualDsl.value)

    // 解析 size
    if (parsed.size) {
      dslConfig.size = parsed.size
    }

    // 解析时间范围
    if (parsed.query?.bool?.must) {
      const must = parsed.query.bool.must
      for (const cond of must) {
        if (cond.range?.['@timestamp']) {
          const gte = cond.range['@timestamp'].gte
          if (gte && gte.startsWith('now-')) {
            dslConfig.timeRange = gte
          }
        }
      }
    }

    // 解析 must 条件
    if (parsed.query?.bool?.must) {
      const must = parsed.query.bool.must
      dslConfig.mustConditions = []
      for (const cond of must) {
        if (!cond.range) {
          const operator = Object.keys(cond)[0]
          const field = Object.keys(cond[operator])[0]
          const value = cond[operator][field]
          dslConfig.mustConditions.push({
            field,
            customField: '',
            operator,
            value: typeof value === 'object' ? JSON.stringify(value) : String(value)
          })
        }
      }
    }

    // 解析 should 条件
    if (parsed.query?.bool?.should) {
      const should = parsed.query.bool.should
      dslConfig.shouldConditions = []
      for (const cond of should) {
        const operator = Object.keys(cond)[0]
        const field = Object.keys(cond[operator])[0]
        const value = cond[operator][field]
        dslConfig.shouldConditions.push({
          field,
          customField: '',
          operator,
          value: typeof value === 'object' ? JSON.stringify(value) : String(value)
        })
      }
    }

    // 解析 minimum_should_match
    if (parsed.query?.bool?.minimum_should_match !== undefined) {
      dslConfig.minimumShouldMatch = parsed.query.bool.minimum_should_match
    }

    ElMessage.success('DSL 已加载到配置')
    previewDsl()
  } catch (e: any) {
    ElMessage.error(`解析失败: ${e.message}`)
  }
}

const clearManualDsl = () => {
  manualDsl.value = ''
}

const queryForm = reactive({
  logLevel: 'ERROR',
  keyword: '',
  traceId: '',
  startTime: null as Date | null,
  endTime: null as Date | null
})

const pagination = reactive({
  page: 1,
  pageSize: 20,
  total: 0
})

const getLevelType = (level: string | null) => {
  if (!level) return ''
  const types: Record<string, string> = {
    ERROR: 'danger',
    WARN: 'warning',
    INFO: 'info',
    DEBUG: '',
    SEVERE: 'danger',
    FATAL: 'danger'
  }
  return types[level.toUpperCase()] || ''
}

const formatTime = (timestamp: string | null) => {
  if (!timestamp) return '-'
  try {
    return new Date(timestamp).toLocaleString('zh-CN')
  } catch {
    return timestamp
  }
}

const shortText = (text: string | null, maxLen: number) => {
  if (!text) return '-'
  return text.length > maxLen ? text.substring(0, maxLen) + '...' : text
}

const handleQuery = async () => {
  loading.value = true
  try {
    // 判断使用手动 DSL 还是配置化 DSL
    let dslQuery: any
    let dslJsonString: string

    if (manualDsl.value.trim()) {
      // 使用手动输入的 DSL
      try {
        dslQuery = JSON.parse(manualDsl.value)
        dslJsonString = manualDsl.value
        // 更新预览
        generatedDsl.value = manualDsl.value
      } catch (e: any) {
        ElMessage.error(`DSL JSON 格式错误: ${e.message}`)
        loading.value = false
        return
      }
    } else {
      // 使用配置化的 DSL 查询
      dslQuery = buildDslQuery()
      dslJsonString = JSON.stringify(dslQuery)
      // 自动更新预览
      generatedDsl.value = dslJsonString
    }

    const params: any = {
      dslQuery: dslJsonString
    }

    const res = await logAnalysisApi.queryLogs(params)
    // axios 拦截器已解包，res 直接是数据对象
    logs.value = res?.logs || []
    pagination.total = res?.total || 0

    if (logs.value.length === 0) {
      ElMessage.info('未查询到符合条件的日志')
    } else {
      ElMessage.success(`查询成功，共 ${pagination.total} 条记录`)
    }

  } catch (error: any) {
    ElMessage.error(`查询失败: ${error.message || '请稍后重试'}`)
    console.error('Query failed:', error)
  } finally {
    loading.value = false
  }
}

const handleReset = () => {
  Object.assign(queryForm, {
    logLevel: 'ERROR',
    keyword: '',
    traceId: '',
    startTime: null,
    endTime: null
  })
  Object.assign(dslConfig, {
    size: 20,
    timeRange: 'now-15m',
    customStartTime: null,
    customEndTime: null,
    mustConditions: [],
    shouldConditions: [
      { field: 'message', customField: '', operator: 'match_phrase', value: 'Caused by:' },
      { field: 'message', customField: '', operator: 'match_phrase', value: '*ExceptionHandler' },
      { field: 'level', customField: '', operator: 'match', value: 'ERROR' },
      { field: 'level', customField: '', operator: 'match', value: 'SEVERE' },
      { field: 'message', customField: '', operator: 'match_phrase', value: '.*Exception' },
      { field: 'message', customField: '', operator: 'match_phrase', value: '.*Error' },
      { field: 'message', customField: '', operator: 'match_phrase', value: 'at org.springframework' }
    ],
    minimumShouldMatch: 1
  })
  generatedDsl.value = ''
  manualDsl.value = ''
  pagination.page = 1
  logs.value = []
  pagination.total = 0
}

const showDetail = (row: LogEntry) => {
  selectedLog.value = row
  detailVisible.value = true
}

const handleAnalyze = async (row: LogEntry) => {
  // 解析日志，区分错误信息和堆栈信息
  const rawLog = row.message || row.stackTrace || ''
  const parsed = parseJavaErrorLog(rawLog)
  const formatted = formatForAnalysis(parsed)

  try {
    // 1. 调后端接口，从 Neo4j 拉取相关代码上下文组装为富提示词
    const result = await aiAnalysisApi.buildLogPrompt({
      errorMessage: formatted.errorSummary || formatted.errorMessage || rawLog,
      errorType: formatted.errorType || '',
      stackTrace: formatted.stackTrace || parsed.rawStackTrace || '',
      projectPath: row.serviceName || parsed.loggerName || ''
    }) as any

    const prompt = result?.prompt || result
    if (!prompt) {
      ElMessage.warning('生成分析提示词失败')
      return
    }

    // 2. 创建 workspace session，通过 PTY 终端发送到 Claude CLI
    const session = await workspaceStore.createSession(
      'log-analysis',
      typeof prompt === 'string' ? prompt : JSON.stringify(prompt)
    )
    router.push({ name: 'ClaudeTerminal', query: { sessionId: session.id } })
    ElMessage.success('已创建日志分析会话')
  } catch (error: any) {
    ElMessage.error(`创建分析会话失败: ${error.message || error}`)
  }
}
</script>

<style scoped>
.mt-4 {
  margin-top: 16px;
}

.dsl-builder-card {
  margin-bottom: 16px;
}

.dsl-builder-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.condition-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.condition-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #f5f7fa;
  border-radius: 6px;
  border: 1px solid #ebeef5;
}

.dsl-preview {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 6px;
  font-size: 12px;
  font-family: 'Consolas', 'Monaco', monospace;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
  margin-bottom: 12px;
}

.form-hint {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}

.manual-dsl-section {
  margin-top: 12px;
}

.manual-dsl-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.recommended-queries {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-top: 12px;
}

.query-card {
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid #e4e7ed;
}

.query-card:hover {
  border-color: #409EFF;
  box-shadow: 0 2px 12px rgba(64, 158, 255, 0.2);
}

.query-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 500;
  color: #303133;
}

.query-card-header .el-icon {
  color: #409EFF;
}

.query-desc {
  margin: 8px 0 0;
  font-size: 12px;
  color: #909399;
}

.dsl-builder-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.dsl-header-actions {
  display: flex;
  gap: 8px;
}

.result-header {
  display: flex;
  align-items: center;
  gap: 12px;
}

.result-count {
  font-size: 13px;
  color: #909399;
}

.trace-id {
  font-family: monospace;
  font-size: 12px;
  color: #409eff;
  cursor: pointer;
}

.log-message {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.error-tag {
  flex-shrink: 0;
}

.log-detail {
  padding: 8px 0;
}

.detail-section {
  margin-top: 20px;
}

.section-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin-bottom: 8px;
  padding-left: 8px;
  border-left: 3px solid #409eff;
}

.message-content {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
}

.stack-content {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 6px;
  font-size: 12px;
  font-family: monospace;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
}

.raw-fields {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 6px;
  font-size: 12px;
  font-family: monospace;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 400px;
  overflow-y: auto;
}

/* 解析后的错误信息样式 */
.error-title {
  display: flex;
  align-items: center;
  gap: 8px;
  border-left-color: #f56c6c;
  color: #f56c6c;
}

.error-info-card {
  background: #fef0f0;
  border-radius: 8px;
  padding: 16px;
  border: 1px solid #fbc4c4;
}

.error-info-card .label {
  font-size: 13px;
  color: #909399;
  margin-right: 8px;
}

.error-type-row {
  margin-bottom: 12px;
}

.error-msg-row {
  margin-bottom: 12px;
}

.error-message {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  color: #303133;
  background: #fff;
  padding: 8px 12px;
  border-radius: 4px;
  display: block;
  margin-top: 4px;
  line-height: 1.5;
}

.error-header-row {
  padding-top: 12px;
  border-top: 1px dashed #fbc4c4;
}

.header-message {
  font-size: 13px;
  color: #606266;
  line-height: 1.5;
}

/* 堆栈帧样式 */
.stack-frames {
  background: #1e1e1e;
  border-radius: 8px;
  padding: 12px;
  max-height: 350px;
  overflow-y: auto;
}

.stack-frame {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #abb2bf;
  border-radius: 4px;
  transition: background 0.2s;
}

.stack-frame:hover {
  background: #2c313a;
}

.stack-frame.frame-project {
  color: #98c379;
  background: rgba(152, 195, 121, 0.1);
}

.stack-frame.frame-project:hover {
  background: rgba(152, 195, 121, 0.2);
}

.frame-index {
  color: #5c6370;
  min-width: 24px;
  text-align: right;
}

.frame-class {
  color: #e5c07b;
}

.frame-method {
  color: #61afef;
}

.frame-location {
  color: #56b6c2;
}

.stack-frame.compact {
  padding: 4px 8px;
  font-size: 11px;
}

/* Caused By 链样式 */
.caused-by-title {
  display: flex;
  align-items: center;
  gap: 8px;
  border-left-color: #e6a23c;
  color: #e6a23c;
}

.caused-by-chain {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.caused-by-item {
  background: #fdf6ec;
  border-radius: 8px;
  padding: 12px;
  border: 1px solid #f5dab1;
}

.cause-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.cause-message {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #606266;
}

.cause-frames {
  padding-left: 12px;
  border-left: 2px solid #e6a23c;
  margin-top: 8px;
}

.more-frames {
  font-size: 11px;
  color: #909399;
  padding-left: 8px;
  font-style: italic;
}

/* 原始日志折叠样式 */
.raw-log {
  font-size: 11px;
  max-height: 250px;
}

/* Markdown 渲染样式 */
.markdown-content {
  background: #f5f7fa;
  padding: 16px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.6;
  max-height: 400px;
  overflow-y: auto;
}

.markdown-content h1, .markdown-content h2, .markdown-content h3,
.markdown-content h4, .markdown-content h5, .markdown-content h6 {
  margin: 0 0 12px 0;
  color: #303133;
}

.markdown-content h1 { font-size: 18px; }
.markdown-content h2 { font-size: 16px; }
.markdown-content h3 { font-size: 15px; }

.markdown-content p {
  margin: 0 0 12px 0;
}

.markdown-content ul, .markdown-content ol {
  margin: 0 0 12px 0;
  padding-left: 20px;
}

.markdown-content li {
  margin-bottom: 6px;
}

.markdown-content code {
  background: #e4e7ed;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
}

.markdown-content pre {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 12px 0;
}

.markdown-content pre code {
  background: transparent;
  color: inherit;
  padding: 0;
}

.markdown-content strong {
  color: #303133;
  font-weight: 600;
}

.markdown-content a {
  color: #409eff;
  text-decoration: none;
}

.error-summary-md {
  background: #fef0f0;
  border: 1px solid #fbc4c4;
}

.root-cause-md {
  background: #fdf6ec;
  border: 1px solid #faecd8;
}

.fix-suggestions-md {
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
}

.code-snippets-md {
  background: #1e1e1e;
  color: #d4d4d4;
}

.code-snippets-md h1, .code-snippets-md h2, .code-snippets-md h3,
.code-snippets-md h4, .code-snippets-md h5, .code-snippets-md h6,
.code-snippets-md strong {
  color: #e5c07b;
}

.code-snippets-md p {
  color: #d4d4d4;
}

/* Task 72: 分组选择提示样式 */
.group-hint {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
}

/* L-11: 进度反馈样式 */
.progress-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.progress-info {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.progress-percent {
  color: #409eff;
  font-weight: 500;
}

.progress-stage {
  color: #909399;
  font-size: 11px;
}
</style>