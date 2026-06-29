# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: natural-language-interaction.spec.ts >> 自然语言交互测试 >> Enter键触发搜索
- Location: e2e\natural-language-interaction.spec.ts:257:3

# Error details

```
Test timeout of 30000ms exceeded.
```

```
TimeoutError: locator.fill: Timeout 10000ms exceeded.
Call log:
  - waiting for locator('.search-box input').first()

```

# Page snapshot

```yaml
- generic [ref=e3]:
  - complementary [ref=e4]:
    - menubar [ref=e5]:
      - menuitem "技能市场" [ref=e6] [cursor=pointer]:
        - img [ref=e8]
        - generic [ref=e10]: 技能市场
      - menuitem "KG Skills 套件" [ref=e11]:
        - img [ref=e13]
        - generic [ref=e15]: KG Skills 套件
      - menuitem "Claude 终端" [ref=e16] [cursor=pointer]:
        - img [ref=e18]
        - generic [ref=e20]: Claude 终端
      - menuitem "APM 调试" [ref=e21] [cursor=pointer]:
        - img [ref=e23]
        - generic [ref=e26]: APM 调试
      - menuitem "增强检索" [ref=e27]:
        - img [ref=e29]
        - generic [ref=e31]: 增强检索
      - menuitem "日志分析" [ref=e32] [cursor=pointer]:
        - img [ref=e34]
        - generic [ref=e36]: 日志分析
      - menuitem "知识图谱" [ref=e37]:
        - img [ref=e39]
        - generic [ref=e41]: 知识图谱
      - menuitem "需求分析大师" [ref=e42] [cursor=pointer]:
        - img [ref=e44]
        - generic [ref=e46]: 需求分析大师
      - menuitem "项目现状分析" [ref=e47] [cursor=pointer]:
        - img [ref=e49]
        - generic [ref=e51]: 项目现状分析
      - menuitem "合入分析" [ref=e52] [cursor=pointer]:
        - img [ref=e54]
        - generic [ref=e57]: 合入分析
      - menuitem "项目管理" [ref=e58] [cursor=pointer]:
        - img [ref=e60]
        - generic [ref=e62]: 项目管理
      - menuitem "系统设置" [ref=e63] [cursor=pointer]:
        - img [ref=e65]
        - generic [ref=e67]: 系统设置
  - generic [ref=e68]:
    - generic [ref=e70]:
      - heading "HiSi DevTool" [level=1] [ref=e71]
      - button "登录" [ref=e74] [cursor=pointer]:
        - generic [ref=e75]: 登录
    - main [ref=e76]:
      - generic [ref=e77]:
        - generic [ref=e78]:
          - generic [ref=e80]:
            - generic [ref=e81]: 项目目录配置
            - generic [ref=e83]: 已配置
          - generic [ref=e85]:
            - generic [ref=e86]:
              - generic [ref=e87]: 项目目录
              - generic [ref=e89]:
                - textbox "项目目录" [ref=e91]:
                  - /placeholder: 请输入项目代码存放目录
                  - text: C:\Users\47583\projects\hisi_dev_tool v4.0
                - button "选择目录" [ref=e94] [cursor=pointer]:
                  - generic [ref=e95]: 选择目录
            - generic [ref=e97]:
              - button "保存配置" [ref=e98] [cursor=pointer]:
                - generic [ref=e99]: 保存配置
              - button "重置" [ref=e100] [cursor=pointer]:
                - generic [ref=e101]: 重置
        - alert [ref=e102]:
          - img [ref=e104]
          - generic [ref=e107]: 请在表格中勾选一个或多个项目以开始分析
        - generic [ref=e110]:
          - tablist [ref=e114]:
            - tab "本地项目" [selected] [ref=e116]
            - tab "远端项目" [ref=e117]
          - tabpanel "本地项目" [ref=e119]:
            - generic [ref=e120]:
              - generic [ref=e121]: 项目管理
              - generic [ref=e122]:
                - button "项目分组" [ref=e123] [cursor=pointer]:
                  - generic [ref=e124]:
                    - img [ref=e126]
                    - text: 项目分组
                - button "一键更新所有仓库" [ref=e128] [cursor=pointer]:
                  - generic [ref=e129]:
                    - img [ref=e131]
                    - text: 一键更新所有仓库
                - button "扫描仓库" [ref=e133] [cursor=pointer]:
                  - generic [ref=e134]:
                    - img [ref=e136]
                    - text: 扫描仓库
                - button "图谱屏蔽目录" [ref=e138] [cursor=pointer]:
                  - generic [ref=e139]:
                    - img [ref=e141]
                    - text: 图谱屏蔽目录
                - button "术语配置" [ref=e143] [cursor=pointer]:
                  - generic [ref=e144]:
                    - img [ref=e146]
                    - text: 术语配置
                - button "克隆项目" [ref=e148] [cursor=pointer]:
                  - generic [ref=e149]:
                    - img [ref=e151]
                    - text: 克隆项目
                - button "跨服务依赖构建 (0)" [disabled] [ref=e153]:
                  - generic [ref=e154]: 跨服务依赖构建 (0)
                - button "确认选择 (0)" [disabled] [ref=e155]:
                  - generic [ref=e156]:
                    - img [ref=e158]
                    - text: 确认选择 (0)
                - button "批量生成图谱 (0)" [disabled] [ref=e160]:
                  - generic [ref=e161]:
                    - img [ref=e163]
                    - text: 批量生成图谱 (0)
            - generic [ref=e165]:
              - generic [ref=e166]:
                - button "hisi开发工具 3 个项目 hisi_dev_tool" [expanded] [ref=e167] [cursor=pointer]:
                  - generic [ref=e169]:
                    - generic [ref=e171]:
                      - checkbox
                    - generic [ref=e173]: hisi开发工具
                    - generic [ref=e175]: 3 个项目
                    - generic [ref=e177]: hisi_dev_tool
                  - img [ref=e179]
                - region "hisi开发工具 3 个项目 hisi_dev_tool" [ref=e181]:
                  - generic [ref=e184]:
                    - table [ref=e186]:
                      - rowgroup [ref=e198]:
                        - row "Select all rows 项目名称 分支 远程地址 状态 来源 图谱状态 向量状态 最近提交 操作" [ref=e199]:
                          - columnheader "Select all rows" [ref=e200]:
                            - generic "Select all rows" [ref=e202] [cursor=pointer]:
                              - generic [ref=e203]:
                                - checkbox "Select all rows"
                          - columnheader "项目名称" [ref=e205]:
                            - generic [ref=e206]: 项目名称
                          - columnheader "分支" [ref=e207]:
                            - generic [ref=e208]: 分支
                          - columnheader "远程地址" [ref=e209]:
                            - generic [ref=e210]: 远程地址
                          - columnheader "状态" [ref=e211]:
                            - generic [ref=e212]: 状态
                          - columnheader "来源" [ref=e213]:
                            - generic [ref=e214]: 来源
                          - columnheader "图谱状态" [ref=e215]:
                            - generic [ref=e216]: 图谱状态
                          - columnheader "向量状态" [ref=e217]:
                            - generic [ref=e218]: 向量状态
                          - columnheader "最近提交" [ref=e219]:
                            - generic [ref=e220]: 最近提交
                          - columnheader "操作" [ref=e221]:
                            - generic [ref=e222]: 操作
                    - table [ref=e227]:
                      - rowgroup [ref=e239]:
                        - row "Select this row hisi-dev-tool v4.4 https://github.com/shenjiangchun/StaticCodeCallChainParser.git Clean 扫描 已生成 已完成 2010/2010 tijiao 选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e240]:
                          - cell "Select this row" [ref=e241]:
                            - generic "Select this row" [ref=e243] [cursor=pointer]:
                              - generic [ref=e244]:
                                - checkbox "Select this row"
                          - cell "hisi-dev-tool" [ref=e246]:
                            - generic [ref=e249]: hisi-dev-tool
                          - cell "v4.4" [ref=e250]:
                            - generic [ref=e251]: v4.4
                          - cell "https://github.com/shenjiangchun/StaticCodeCallChainParser.git" [ref=e252]:
                            - generic [ref=e253]: https://github.com/shenjiangchun/StaticCodeCallChainParser.git
                          - cell "Clean" [ref=e254]:
                            - generic [ref=e257]: Clean
                          - cell "扫描" [ref=e258]:
                            - generic [ref=e261]: 扫描
                          - cell "已生成" [ref=e262]:
                            - generic [ref=e264]:
                              - 'generic "知识图谱已生成 方法节点: 2010 调用关系: 1556 入口点: 151" [ref=e265]'
                              - generic [ref=e266]: 已生成
                          - cell "已完成 2010/2010" [ref=e267]:
                            - generic [ref=e269]:
                              - generic [ref=e270]:
                                - 'generic "向量已生成 处理方法数: 2010 耗时: 752.0s" [ref=e271]'
                                - generic [ref=e272]: 已完成
                              - generic [ref=e273]: 2010/2010
                          - cell "tijiao" [ref=e274]:
                            - generic [ref=e275]: tijiao
                          - cell "选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e276]:
                            - generic [ref=e277]:
                              - button "选择" [ref=e278] [cursor=pointer]:
                                - generic [ref=e279]:
                                  - img [ref=e281]
                                  - text: 选择
                              - button "提交分析" [ref=e283] [cursor=pointer]:
                                - generic [ref=e284]:
                                  - img [ref=e286]
                                  - text: 提交分析
                              - button "生成图谱" [ref=e288] [cursor=pointer]:
                                - generic [ref=e289]:
                                  - img [ref=e291]
                                  - text: 生成图谱
                              - button "描述&向量" [ref=e293] [cursor=pointer]:
                                - generic [ref=e294]:
                                  - img [ref=e296]
                                  - text: 描述&向量
                              - button "Git 操作" [ref=e300] [cursor=pointer]:
                                - generic [ref=e301]:
                                  - img [ref=e303]
                                  - text: Git 操作
                              - button "拉取" [ref=e305] [cursor=pointer]:
                                - generic [ref=e306]: 拉取
                              - button "图谱刷新" [ref=e307] [cursor=pointer]:
                                - generic [ref=e308]:
                                  - img [ref=e310]
                                  - text: 图谱刷新
                              - button "删除" [ref=e312] [cursor=pointer]:
                                - generic [ref=e313]: 删除
                        - row "Select this row hisi-dev-tool-frontend v4.3 https://github.com/shenjiangchun/hisi-dev-tool-frontend.git Clean 扫描 未生成知识图谱 未生成 未生成向量 未生成 Merge remote-tracking branch 'origin/v4.3' into v4.3 选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e314]:
                          - cell "Select this row" [ref=e315]:
                            - generic "Select this row" [ref=e317] [cursor=pointer]:
                              - generic [ref=e318]:
                                - checkbox "Select this row"
                          - cell "hisi-dev-tool-frontend" [ref=e320]:
                            - generic [ref=e323]: hisi-dev-tool-frontend
                          - cell "v4.3" [ref=e324]:
                            - generic [ref=e325]: v4.3
                          - cell "https://github.com/shenjiangchun/hisi-dev-tool-frontend.git" [ref=e326]:
                            - generic [ref=e327]: https://github.com/shenjiangchun/hisi-dev-tool-frontend.git
                          - cell "Clean" [ref=e328]:
                            - generic [ref=e331]: Clean
                          - cell "扫描" [ref=e332]:
                            - generic [ref=e335]: 扫描
                          - cell "未生成知识图谱 未生成" [ref=e336]:
                            - generic [ref=e338]:
                              - generic "未生成知识图谱" [ref=e339]
                              - generic [ref=e340]: 未生成
                          - cell "未生成向量 未生成" [ref=e341]:
                            - generic [ref=e344]:
                              - generic "未生成向量" [ref=e345]
                              - generic [ref=e346]: 未生成
                          - cell "Merge remote-tracking branch 'origin/v4.3' into v4.3" [ref=e347]:
                            - generic [ref=e348]: Merge remote-tracking branch 'origin/v4.3' into v4.3
                          - cell "选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e349]:
                            - generic [ref=e350]:
                              - button "选择" [ref=e351] [cursor=pointer]:
                                - generic [ref=e352]:
                                  - img [ref=e354]
                                  - text: 选择
                              - button "提交分析" [ref=e356] [cursor=pointer]:
                                - generic [ref=e357]:
                                  - img [ref=e359]
                                  - text: 提交分析
                              - button "生成图谱" [ref=e361] [cursor=pointer]:
                                - generic [ref=e362]:
                                  - img [ref=e364]
                                  - text: 生成图谱
                              - button "描述&向量" [ref=e366] [cursor=pointer]:
                                - generic [ref=e367]:
                                  - img [ref=e369]
                                  - text: 描述&向量
                              - button "Git 操作" [ref=e373] [cursor=pointer]:
                                - generic [ref=e374]:
                                  - img [ref=e376]
                                  - text: Git 操作
                              - button "拉取" [ref=e378] [cursor=pointer]:
                                - generic [ref=e379]: 拉取
                              - button "图谱刷新" [ref=e380] [cursor=pointer]:
                                - generic [ref=e381]:
                                  - img [ref=e383]
                                  - text: 图谱刷新
                              - button "删除" [ref=e385] [cursor=pointer]:
                                - generic [ref=e386]: 删除
                        - 'row "Select this row hisi-mcp-server master https://github.com/shenjiangchun/hisi-mcp-server.git Modified 扫描 未生成知识图谱 未生成 生成失败: 任务超时（超过1天） 失败 refactor: remove kg_callers/kg_callees, add kg_root_entries/kg_callees_tree 选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e387]':
                          - cell "Select this row" [ref=e388]:
                            - generic "Select this row" [ref=e390] [cursor=pointer]:
                              - generic [ref=e391]:
                                - checkbox "Select this row"
                          - cell "hisi-mcp-server" [ref=e393]:
                            - generic [ref=e396]: hisi-mcp-server
                          - cell "master" [ref=e397]:
                            - generic [ref=e398]: master
                          - cell "https://github.com/shenjiangchun/hisi-mcp-server.git" [ref=e399]:
                            - generic [ref=e400]: https://github.com/shenjiangchun/hisi-mcp-server.git
                          - cell "Modified" [ref=e401]:
                            - generic [ref=e404]: Modified
                          - cell "扫描" [ref=e405]:
                            - generic [ref=e408]: 扫描
                          - cell "未生成知识图谱 未生成" [ref=e409]:
                            - generic [ref=e411]:
                              - generic "未生成知识图谱" [ref=e412]
                              - generic [ref=e413]: 未生成
                          - 'cell "生成失败: 任务超时（超过1天） 失败" [ref=e414]':
                            - generic [ref=e417]:
                              - 'generic "生成失败: 任务超时（超过1天）" [ref=e418]'
                              - generic [ref=e419]: 失败
                          - 'cell "refactor: remove kg_callers/kg_callees, add kg_root_entries/kg_callees_tree" [ref=e420]':
                            - generic [ref=e421]: "refactor: remove kg_callers/kg_callees, add kg_root_entries/kg_callees_tree"
                          - cell "选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e422]:
                            - generic [ref=e423]:
                              - button "选择" [ref=e424] [cursor=pointer]:
                                - generic [ref=e425]:
                                  - img [ref=e427]
                                  - text: 选择
                              - button "提交分析" [ref=e429] [cursor=pointer]:
                                - generic [ref=e430]:
                                  - img [ref=e432]
                                  - text: 提交分析
                              - button "生成图谱" [ref=e434] [cursor=pointer]:
                                - generic [ref=e435]:
                                  - img [ref=e437]
                                  - text: 生成图谱
                              - button "描述&向量" [ref=e439] [cursor=pointer]:
                                - generic [ref=e440]:
                                  - img [ref=e442]
                                  - text: 描述&向量
                              - button "Git 操作" [ref=e446] [cursor=pointer]:
                                - generic [ref=e447]:
                                  - img [ref=e449]
                                  - text: Git 操作
                              - button "拉取" [ref=e451] [cursor=pointer]:
                                - generic [ref=e452]: 拉取
                              - button "图谱刷新" [ref=e453] [cursor=pointer]:
                                - generic [ref=e454]:
                                  - img [ref=e456]
                                  - text: 图谱刷新
                              - button "删除" [ref=e458] [cursor=pointer]:
                                - generic [ref=e459]: 删除
              - generic [ref=e460]:
                - button "未分组 2 个项目" [expanded] [ref=e461] [cursor=pointer]:
                  - generic [ref=e463]:
                    - generic [ref=e465]:
                      - checkbox
                    - generic [ref=e467]: 未分组
                    - generic [ref=e469]: 2 个项目
                  - img [ref=e471]
                - region "未分组 2 个项目" [ref=e473]:
                  - generic [ref=e476]:
                    - table [ref=e478]:
                      - rowgroup [ref=e490]:
                        - row "Select all rows 项目名称 分支 远程地址 状态 来源 图谱状态 向量状态 最近提交 操作" [ref=e491]:
                          - columnheader "Select all rows" [ref=e492]:
                            - generic "Select all rows" [ref=e494] [cursor=pointer]:
                              - generic [ref=e495]:
                                - checkbox "Select all rows"
                          - columnheader "项目名称" [ref=e497]:
                            - generic [ref=e498]: 项目名称
                          - columnheader "分支" [ref=e499]:
                            - generic [ref=e500]: 分支
                          - columnheader "远程地址" [ref=e501]:
                            - generic [ref=e502]: 远程地址
                          - columnheader "状态" [ref=e503]:
                            - generic [ref=e504]: 状态
                          - columnheader "来源" [ref=e505]:
                            - generic [ref=e506]: 来源
                          - columnheader "图谱状态" [ref=e507]:
                            - generic [ref=e508]: 图谱状态
                          - columnheader "向量状态" [ref=e509]:
                            - generic [ref=e510]: 向量状态
                          - columnheader "最近提交" [ref=e511]:
                            - generic [ref=e512]: 最近提交
                          - columnheader "操作" [ref=e513]:
                            - generic [ref=e514]: 操作
                    - table [ref=e519]:
                      - rowgroup [ref=e531]:
                        - 'row "Select this row demo-django master - Clean 扫描 已生成 已完成 26/26 chore: initial Django demo for KG scanner coverage 选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e532]':
                          - cell "Select this row" [ref=e533]:
                            - generic "Select this row" [ref=e535] [cursor=pointer]:
                              - generic [ref=e536]:
                                - checkbox "Select this row"
                          - cell "demo-django" [ref=e538]:
                            - generic [ref=e541]: demo-django
                          - cell "master" [ref=e542]:
                            - generic [ref=e543]: master
                          - cell "-" [ref=e544]:
                            - generic [ref=e545]: "-"
                          - cell "Clean" [ref=e546]:
                            - generic [ref=e549]: Clean
                          - cell "扫描" [ref=e550]:
                            - generic [ref=e553]: 扫描
                          - cell "已生成" [ref=e554]:
                            - generic [ref=e556]:
                              - 'generic "知识图谱已生成 方法节点: 26 调用关系: 2 入口点: 3" [ref=e557]'
                              - generic [ref=e558]: 已生成
                          - cell "已完成 26/26" [ref=e559]:
                            - generic [ref=e561]:
                              - generic [ref=e562]:
                                - 'generic "向量已生成 处理方法数: 26 耗时: 14.0s" [ref=e563]'
                                - generic [ref=e564]: 已完成
                              - generic [ref=e565]: 26/26
                          - 'cell "chore: initial Django demo for KG scanner coverage" [ref=e566]':
                            - generic [ref=e567]: "chore: initial Django demo for KG scanner coverage"
                          - cell "选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e568]:
                            - generic [ref=e569]:
                              - button "选择" [ref=e570] [cursor=pointer]:
                                - generic [ref=e571]:
                                  - img [ref=e573]
                                  - text: 选择
                              - button "提交分析" [ref=e575] [cursor=pointer]:
                                - generic [ref=e576]:
                                  - img [ref=e578]
                                  - text: 提交分析
                              - button "生成图谱" [ref=e580] [cursor=pointer]:
                                - generic [ref=e581]:
                                  - img [ref=e583]
                                  - text: 生成图谱
                              - button "描述&向量" [ref=e585] [cursor=pointer]:
                                - generic [ref=e586]:
                                  - img [ref=e588]
                                  - text: 描述&向量
                              - button "Git 操作" [ref=e592] [cursor=pointer]:
                                - generic [ref=e593]:
                                  - img [ref=e595]
                                  - text: Git 操作
                              - button "拉取" [ref=e597] [cursor=pointer]:
                                - generic [ref=e598]: 拉取
                              - button "图谱刷新" [ref=e599] [cursor=pointer]:
                                - generic [ref=e600]:
                                  - img [ref=e602]
                                  - text: 图谱刷新
                              - button "删除" [ref=e604] [cursor=pointer]:
                                - generic [ref=e605]: 删除
                        - 'row "Select this row demo-fastapi master - Clean 扫描 已生成 已完成 37/37 chore: initial FastAPI demo for KG scanner coverage 选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e606]':
                          - cell "Select this row" [ref=e607]:
                            - generic "Select this row" [ref=e609] [cursor=pointer]:
                              - generic [ref=e610]:
                                - checkbox "Select this row"
                          - cell "demo-fastapi" [ref=e612]:
                            - generic [ref=e615]: demo-fastapi
                          - cell "master" [ref=e616]:
                            - generic [ref=e617]: master
                          - cell "-" [ref=e618]:
                            - generic [ref=e619]: "-"
                          - cell "Clean" [ref=e620]:
                            - generic [ref=e623]: Clean
                          - cell "扫描" [ref=e624]:
                            - generic [ref=e627]: 扫描
                          - cell "已生成" [ref=e628]:
                            - generic [ref=e630]:
                              - 'generic "知识图谱已生成 方法节点: 37 调用关系: 8 入口点: 5" [ref=e631]'
                              - generic [ref=e632]: 已生成
                          - cell "已完成 37/37" [ref=e633]:
                            - generic [ref=e635]:
                              - generic [ref=e636]:
                                - 'generic "向量已生成 处理方法数: 37 耗时: 527.0s" [ref=e637]'
                                - generic [ref=e638]: 已完成
                              - generic [ref=e639]: 37/37
                          - 'cell "chore: initial FastAPI demo for KG scanner coverage" [ref=e640]':
                            - generic [ref=e641]: "chore: initial FastAPI demo for KG scanner coverage"
                          - cell "选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e642]:
                            - generic [ref=e643]:
                              - button "选择" [ref=e644] [cursor=pointer]:
                                - generic [ref=e645]:
                                  - img [ref=e647]
                                  - text: 选择
                              - button "提交分析" [ref=e649] [cursor=pointer]:
                                - generic [ref=e650]:
                                  - img [ref=e652]
                                  - text: 提交分析
                              - button "生成图谱" [ref=e654] [cursor=pointer]:
                                - generic [ref=e655]:
                                  - img [ref=e657]
                                  - text: 生成图谱
                              - button "描述&向量" [ref=e659] [cursor=pointer]:
                                - generic [ref=e660]:
                                  - img [ref=e662]
                                  - text: 描述&向量
                              - button "Git 操作" [ref=e666] [cursor=pointer]:
                                - generic [ref=e667]:
                                  - img [ref=e669]
                                  - text: Git 操作
                              - button "拉取" [ref=e671] [cursor=pointer]:
                                - generic [ref=e672]: 拉取
                              - button "图谱刷新" [ref=e673] [cursor=pointer]:
                                - generic [ref=e674]:
                                  - img [ref=e676]
                                  - text: 图谱刷新
                              - button "删除" [ref=e678] [cursor=pointer]:
                                - generic [ref=e679]: 删除
```

# Test source

```ts
  163 |     await searchInput.press('Enter')
  164 | 
  165 |     await page.waitForTimeout(3000)
  166 | 
  167 |     // 尝试选择结果
  168 |     const resultItems = page.locator('.result-item')
  169 |     const itemCount = await resultItems.count()
  170 | 
  171 |     if (itemCount > 0) {
  172 |       // 点击第一个结果
  173 |       await resultItems.first().click()
  174 |       await page.waitForTimeout(500)
  175 | 
  176 |       // 验证选中状态
  177 |       await expect(resultItems.first()).toHaveClass(/selected/)
  178 | 
  179 |       // 验证预览面板显示
  180 |       const previewPanel = page.locator('.preview-column, .code-preview-panel')
  181 |       await expect(previewPanel).toBeVisible()
  182 |     }
  183 |   })
  184 | 
  185 |   /**
  186 |    * 测试场景6: 搜索建议和历史
  187 |    */
  188 |   test('搜索建议和历史功能', async ({ page }) => {
  189 |     await page.goto(`${BASE_URL}/search`)
  190 |     await page.waitForLoadState('networkidle')
  191 | 
  192 |     // 检查历史区域
  193 |     const historySection = page.locator('.search-history')
  194 |     if (await historySection.isVisible()) {
  195 |       const historyTags = historySection.locator('.history-tag')
  196 |       const historyCount = await historyTags.count()
  197 | 
  198 |       if (historyCount > 0) {
  199 |         // 点击历史记录项
  200 |         await historyTags.first().click()
  201 | 
  202 |         // 验证输入框被填充
  203 |         const searchInput = page.locator('.search-box input').first()
  204 |         const inputValue = await searchInput.inputValue()
  205 |         expect(inputValue.length).toBeGreaterThan(0)
  206 |       }
  207 |     }
  208 |   })
  209 | 
  210 |   /**
  211 |    * 测试场景7: 相关度阈值调节
  212 |    */
  213 |   test('相关度阈值滑块调节', async ({ page }) => {
  214 |     await page.goto(`${BASE_URL}/search`)
  215 |     await page.waitForLoadState('networkidle')
  216 | 
  217 |     // 找到阈值滑块
  218 |     const thresholdSlider = page.locator('.search-filters .el-slider')
  219 | 
  220 |     if (await thresholdSlider.isVisible()) {
  221 |       // 获取滑块初始值
  222 |       const sliderRunway = thresholdSlider.locator('.el-slider__runway')
  223 | 
  224 |       // 验证滑块存在并可交互
  225 |       await expect(sliderRunway).toBeVisible()
  226 | 
  227 |       // 滑动到不同位置（模拟拖动）
  228 |       const sliderBar = thresholdSlider.locator('.el-slider__bar')
  229 |       await expect(sliderBar).toBeVisible()
  230 |     }
  231 |   })
  232 | 
  233 |   /**
  234 |    * 测试场景8: 清空搜索
  235 |    */
  236 |   test('清空搜索输入', async ({ page }) => {
  237 |     await page.goto(`${BASE_URL}/search`)
  238 |     await page.waitForLoadState('networkidle')
  239 | 
  240 |     // 输入搜索内容
  241 |     const searchInput = page.locator('.search-box input').first()
  242 |     await searchInput.fill('测试搜索内容')
  243 | 
  244 |     // 点击清空按钮
  245 |     const clearButton = page.locator('.el-input__clear')
  246 |     if (await clearButton.isVisible()) {
  247 |       await clearButton.click()
  248 | 
  249 |       // 验证输入被清空
  250 |       await expect(searchInput).toHaveValue('')
  251 |     }
  252 |   })
  253 | 
  254 |   /**
  255 |    * 测试场景9: Enter键触发搜索
  256 |    */
  257 |   test('Enter键触发搜索', async ({ page }) => {
  258 |     await page.goto(`${BASE_URL}/search`)
  259 |     await page.waitForLoadState('networkidle')
  260 | 
  261 |     // 输入搜索内容
  262 |     const searchInput = page.locator('.search-box input').first()
> 263 |     await searchInput.fill('配置文件读取')
      |                       ^ TimeoutError: locator.fill: Timeout 10000ms exceeded.
  264 | 
  265 |     // 按Enter键
  266 |     await searchInput.press('Enter')
  267 | 
  268 |     // 等待搜索响应
  269 |     await page.waitForTimeout(2000)
  270 | 
  271 |     // 验证搜索已触发（加载状态或结果）
  272 |     const searchButton = page.locator('button:has-text("搜索")')
  273 |     const isLoading = await searchButton.getAttribute('loading')
  274 | 
  275 |     // 或者检查结果面板状态
  276 |     const resultsPanel = page.locator('.search-results-panel')
  277 |     await expect(resultsPanel).toBeVisible({ timeout: 10000 })
  278 |   })
  279 | 
  280 |   /**
  281 |    * 测试场景10: 加载更多结果
  282 |    */
  283 |   test('加载更多搜索结果', async ({ page }) => {
  284 |     await page.goto(`${BASE_URL}/search`)
  285 |     await page.waitForLoadState('networkidle')
  286 | 
  287 |     // 执行搜索
  288 |     const searchInput = page.locator('.search-box input').first()
  289 |     await searchInput.fill('服务调用')
  290 |     await searchInput.press('Enter')
  291 | 
  292 |     await page.waitForTimeout(3000)
  293 | 
  294 |     // 检查是否有"加载更多"按钮
  295 |     const loadMoreButton = page.locator('.load-more button:has-text("加载更多")')
  296 | 
  297 |     if (await loadMoreButton.isVisible()) {
  298 |       await loadMoreButton.click()
  299 |       await page.waitForTimeout(2000)
  300 | 
  301 |       // 验证结果数量增加
  302 |       const resultItems = page.locator('.result-item')
  303 |       const itemCount = await resultItems.count()
  304 |       expect(itemCount).toBeGreaterThan(0)
  305 |     }
  306 |   })
  307 | })
  308 | 
  309 | /**
  310 |  * 智能诊断自然语言交互测试
  311 |  */
  312 | test.describe('智能诊断自然语言交互测试', () => {
  313 | 
  314 |   const BASE_URL = process.env.FRONTEND_URL || 'http://localhost:5173'
  315 | 
  316 |   test.beforeEach(async ({ page }) => {
  317 |     await page.goto(`${BASE_URL}/diagnostic`)
  318 |     await page.waitForLoadState('networkidle')
  319 |   })
  320 | 
  321 |   /**
  322 |    * 测试场景: 用户输入问题描述进行诊断
  323 |    */
  324 |   test('用户输入自然语言问题描述', async ({ page }) => {
  325 |     // 验证诊断页面加载
  326 |     const diagnosisPanel = page.locator('.agent-diagnosis-panel')
  327 |     await expect(diagnosisPanel).toBeVisible({ timeout: 10000 })
  328 | 
  329 |     // 输入问题描述
  330 |     const textarea = page.locator('.input-section textarea, textarea').first()
  331 |     await textarea.fill('NullPointerException at UserService.login() line 123')
  332 | 
  333 |     // 验证输入值
  334 |     await expect(textarea).toHaveValue('NullPointerException at UserService.login() line 123')
  335 | 
  336 |     // 点击开始诊断按钮
  337 |     const startButton = page.locator('button:has-text("开始诊断")')
  338 |     await expect(startButton).toBeVisible()
  339 |   })
  340 | 
  341 |   /**
  342 |    * 测试场景: 诊断过程显示Agent执行状态
  343 |    */
  344 |   test('诊断过程显示Agent状态', async ({ page }) => {
  345 |     const textarea = page.locator('.input-section textarea').first()
  346 |     await textarea.fill('StackOverflowError in recursive function')
  347 | 
  348 |     // 开始诊断
  349 |     const startButton = page.locator('button:has-text("开始诊断")')
  350 |     await startButton.click()
  351 | 
  352 |     // 等待诊断开始
  353 |     await page.waitForTimeout(2000)
  354 | 
  355 |     // 验证进度区域
  356 |     const progressSection = page.locator('.progress-section')
  357 |     if (await progressSection.isVisible()) {
  358 |       // 验证进度显示
  359 |       const progressValue = progressSection.locator('.progress-value')
  360 |       await expect(progressValue).toBeVisible()
  361 |     }
  362 | 
  363 |     // 验证Agent列表
```