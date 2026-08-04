# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: ram-menu.spec.ts >> 需求分析大师菜单测试 >> 需求分析大师菜单可点击并导航到/ram
- Location: e2e\ram-menu.spec.ts:20:3

# Error details

```
TimeoutError: page.waitForLoadState: Timeout 30000ms exceeded.
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
                - button "扫描仓库" [disabled]:
                  - generic:
                    - img
                  - generic:
                    - generic:
                      - img
                    - text: 扫描仓库
                - button "图谱屏蔽目录" [ref=e133] [cursor=pointer]:
                  - generic [ref=e134]:
                    - img [ref=e136]
                    - text: 图谱屏蔽目录
                - button "术语配置" [ref=e138] [cursor=pointer]:
                  - generic [ref=e139]:
                    - img [ref=e141]
                    - text: 术语配置
                - button "克隆项目" [ref=e143] [cursor=pointer]:
                  - generic [ref=e144]:
                    - img [ref=e146]
                    - text: 克隆项目
                - button "跨服务依赖构建 (0)" [disabled] [ref=e148]:
                  - generic [ref=e149]: 跨服务依赖构建 (0)
                - button "确认选择 (0)" [disabled] [ref=e150]:
                  - generic [ref=e151]:
                    - img [ref=e153]
                    - text: 确认选择 (0)
                - button "批量生成图谱 (0)" [disabled] [ref=e155]:
                  - generic [ref=e156]:
                    - img [ref=e158]
                    - text: 批量生成图谱 (0)
            - generic [ref=e160]:
              - generic [ref=e161]:
                - button "hisi开发工具 3 个项目 hisi_dev_tool" [expanded] [ref=e162] [cursor=pointer]:
                  - generic [ref=e164]:
                    - generic [ref=e166]:
                      - checkbox
                    - generic [ref=e168]: hisi开发工具
                    - generic [ref=e170]: 3 个项目
                    - generic [ref=e172]: hisi_dev_tool
                  - img [ref=e174]
                - region "hisi开发工具 3 个项目 hisi_dev_tool" [ref=e176]:
                  - generic [ref=e179]:
                    - table [ref=e181]:
                      - rowgroup [ref=e193]:
                        - row "Select all rows 项目名称 分支 远程地址 状态 来源 图谱状态 向量状态 最近提交 操作" [ref=e194]:
                          - columnheader "Select all rows" [ref=e195]:
                            - generic "Select all rows" [ref=e197] [cursor=pointer]:
                              - generic [ref=e198]:
                                - checkbox "Select all rows"
                          - columnheader "项目名称" [ref=e200]:
                            - generic [ref=e201]: 项目名称
                          - columnheader "分支" [ref=e202]:
                            - generic [ref=e203]: 分支
                          - columnheader "远程地址" [ref=e204]:
                            - generic [ref=e205]: 远程地址
                          - columnheader "状态" [ref=e206]:
                            - generic [ref=e207]: 状态
                          - columnheader "来源" [ref=e208]:
                            - generic [ref=e209]: 来源
                          - columnheader "图谱状态" [ref=e210]:
                            - generic [ref=e211]: 图谱状态
                          - columnheader "向量状态" [ref=e212]:
                            - generic [ref=e213]: 向量状态
                          - columnheader "最近提交" [ref=e214]:
                            - generic [ref=e215]: 最近提交
                          - columnheader "操作" [ref=e216]:
                            - generic [ref=e217]: 操作
                    - table [ref=e222]:
                      - rowgroup [ref=e234]:
                        - row "Select this row hisi-dev-tool v4.4 https://github.com/shenjiangchun/StaticCodeCallChainParser.git Clean 扫描 未生成知识图谱 未生成 已完成 2010/2010 tijiao 选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e235]:
                          - cell "Select this row" [ref=e236]:
                            - generic "Select this row" [ref=e238] [cursor=pointer]:
                              - generic [ref=e239]:
                                - checkbox "Select this row"
                          - cell "hisi-dev-tool" [ref=e241]:
                            - generic [ref=e244]: hisi-dev-tool
                          - cell "v4.4" [ref=e245]:
                            - generic [ref=e246]: v4.4
                          - cell "https://github.com/shenjiangchun/StaticCodeCallChainParser.git" [ref=e247]:
                            - generic [ref=e248]: https://github.com/shenjiangchun/StaticCodeCallChainParser.git
                          - cell "Clean" [ref=e249]:
                            - generic [ref=e252]: Clean
                          - cell "扫描" [ref=e253]:
                            - generic [ref=e256]: 扫描
                          - cell "未生成知识图谱 未生成" [ref=e257]:
                            - generic [ref=e259]:
                              - generic "未生成知识图谱" [ref=e260]
                              - generic [ref=e261]: 未生成
                          - cell "已完成 2010/2010" [ref=e262]:
                            - generic [ref=e264]:
                              - generic [ref=e265]:
                                - 'generic "向量已生成 处理方法数: 2010 耗时: 752.0s" [ref=e266]'
                                - generic [ref=e267]: 已完成
                              - generic [ref=e268]: 2010/2010
                          - cell "tijiao" [ref=e269]:
                            - generic [ref=e270]: tijiao
                          - cell "选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e271]:
                            - generic [ref=e272]:
                              - button "选择" [ref=e273] [cursor=pointer]:
                                - generic [ref=e274]:
                                  - img [ref=e276]
                                  - text: 选择
                              - button "提交分析" [ref=e278] [cursor=pointer]:
                                - generic [ref=e279]:
                                  - img [ref=e281]
                                  - text: 提交分析
                              - button "生成图谱" [ref=e283] [cursor=pointer]:
                                - generic [ref=e284]:
                                  - img [ref=e286]
                                  - text: 生成图谱
                              - button "描述&向量" [ref=e288] [cursor=pointer]:
                                - generic [ref=e289]:
                                  - img [ref=e291]
                                  - text: 描述&向量
                              - button "Git 操作" [ref=e295] [cursor=pointer]:
                                - generic [ref=e296]:
                                  - img [ref=e298]
                                  - text: Git 操作
                              - button "拉取" [ref=e300] [cursor=pointer]:
                                - generic [ref=e301]: 拉取
                              - button "图谱刷新" [ref=e302] [cursor=pointer]:
                                - generic [ref=e303]:
                                  - img [ref=e305]
                                  - text: 图谱刷新
                              - button "删除" [ref=e307] [cursor=pointer]:
                                - generic [ref=e308]: 删除
                        - row "Select this row hisi-dev-tool-frontend v4.3 https://github.com/shenjiangchun/hisi-dev-tool-frontend.git Clean 扫描 未生成知识图谱 未生成 未生成向量 未生成 Merge remote-tracking branch 'origin/v4.3' into v4.3 选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e309]:
                          - cell "Select this row" [ref=e310]:
                            - generic "Select this row" [ref=e312] [cursor=pointer]:
                              - generic [ref=e313]:
                                - checkbox "Select this row"
                          - cell "hisi-dev-tool-frontend" [ref=e315]:
                            - generic [ref=e318]: hisi-dev-tool-frontend
                          - cell "v4.3" [ref=e319]:
                            - generic [ref=e320]: v4.3
                          - cell "https://github.com/shenjiangchun/hisi-dev-tool-frontend.git" [ref=e321]:
                            - generic [ref=e322]: https://github.com/shenjiangchun/hisi-dev-tool-frontend.git
                          - cell "Clean" [ref=e323]:
                            - generic [ref=e326]: Clean
                          - cell "扫描" [ref=e327]:
                            - generic [ref=e330]: 扫描
                          - cell "未生成知识图谱 未生成" [ref=e331]:
                            - generic [ref=e333]:
                              - generic "未生成知识图谱" [ref=e334]
                              - generic [ref=e335]: 未生成
                          - cell "未生成向量 未生成" [ref=e336]:
                            - generic [ref=e339]:
                              - generic "未生成向量" [ref=e340]
                              - generic [ref=e341]: 未生成
                          - cell "Merge remote-tracking branch 'origin/v4.3' into v4.3" [ref=e342]:
                            - generic [ref=e343]: Merge remote-tracking branch 'origin/v4.3' into v4.3
                          - cell "选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e344]:
                            - generic [ref=e345]:
                              - button "选择" [ref=e346] [cursor=pointer]:
                                - generic [ref=e347]:
                                  - img [ref=e349]
                                  - text: 选择
                              - button "提交分析" [ref=e351] [cursor=pointer]:
                                - generic [ref=e352]:
                                  - img [ref=e354]
                                  - text: 提交分析
                              - button "生成图谱" [ref=e356] [cursor=pointer]:
                                - generic [ref=e357]:
                                  - img [ref=e359]
                                  - text: 生成图谱
                              - button "描述&向量" [ref=e361] [cursor=pointer]:
                                - generic [ref=e362]:
                                  - img [ref=e364]
                                  - text: 描述&向量
                              - button "Git 操作" [ref=e368] [cursor=pointer]:
                                - generic [ref=e369]:
                                  - img [ref=e371]
                                  - text: Git 操作
                              - button "拉取" [ref=e373] [cursor=pointer]:
                                - generic [ref=e374]: 拉取
                              - button "图谱刷新" [ref=e375] [cursor=pointer]:
                                - generic [ref=e376]:
                                  - img [ref=e378]
                                  - text: 图谱刷新
                              - button "删除" [ref=e380] [cursor=pointer]:
                                - generic [ref=e381]: 删除
                        - 'row "Select this row hisi-mcp-server master https://github.com/shenjiangchun/hisi-mcp-server.git Modified 扫描 未生成知识图谱 未生成 生成失败: 任务超时（超过1天） 失败 refactor: remove kg_callers/kg_callees, add kg_root_entries/kg_callees_tree 选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e382]':
                          - cell "Select this row" [ref=e383]:
                            - generic "Select this row" [ref=e385] [cursor=pointer]:
                              - generic [ref=e386]:
                                - checkbox "Select this row"
                          - cell "hisi-mcp-server" [ref=e388]:
                            - generic [ref=e391]: hisi-mcp-server
                          - cell "master" [ref=e392]:
                            - generic [ref=e393]: master
                          - cell "https://github.com/shenjiangchun/hisi-mcp-server.git" [ref=e394]:
                            - generic [ref=e395]: https://github.com/shenjiangchun/hisi-mcp-server.git
                          - cell "Modified" [ref=e396]:
                            - generic [ref=e399]: Modified
                          - cell "扫描" [ref=e400]:
                            - generic [ref=e403]: 扫描
                          - cell "未生成知识图谱 未生成" [ref=e404]:
                            - generic [ref=e406]:
                              - generic "未生成知识图谱" [ref=e407]
                              - generic [ref=e408]: 未生成
                          - 'cell "生成失败: 任务超时（超过1天） 失败" [ref=e409]':
                            - generic [ref=e412]:
                              - 'generic "生成失败: 任务超时（超过1天）" [ref=e413]'
                              - generic [ref=e414]: 失败
                          - 'cell "refactor: remove kg_callers/kg_callees, add kg_root_entries/kg_callees_tree" [ref=e415]':
                            - generic [ref=e416]: "refactor: remove kg_callers/kg_callees, add kg_root_entries/kg_callees_tree"
                          - cell "选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e417]:
                            - generic [ref=e418]:
                              - button "选择" [ref=e419] [cursor=pointer]:
                                - generic [ref=e420]:
                                  - img [ref=e422]
                                  - text: 选择
                              - button "提交分析" [ref=e424] [cursor=pointer]:
                                - generic [ref=e425]:
                                  - img [ref=e427]
                                  - text: 提交分析
                              - button "生成图谱" [ref=e429] [cursor=pointer]:
                                - generic [ref=e430]:
                                  - img [ref=e432]
                                  - text: 生成图谱
                              - button "描述&向量" [ref=e434] [cursor=pointer]:
                                - generic [ref=e435]:
                                  - img [ref=e437]
                                  - text: 描述&向量
                              - button "Git 操作" [ref=e441] [cursor=pointer]:
                                - generic [ref=e442]:
                                  - img [ref=e444]
                                  - text: Git 操作
                              - button "拉取" [ref=e446] [cursor=pointer]:
                                - generic [ref=e447]: 拉取
                              - button "图谱刷新" [ref=e448] [cursor=pointer]:
                                - generic [ref=e449]:
                                  - img [ref=e451]
                                  - text: 图谱刷新
                              - button "删除" [ref=e453] [cursor=pointer]:
                                - generic [ref=e454]: 删除
              - generic [ref=e455]:
                - button "未分组 2 个项目" [expanded] [ref=e456] [cursor=pointer]:
                  - generic [ref=e458]:
                    - generic [ref=e460]:
                      - checkbox
                    - generic [ref=e462]: 未分组
                    - generic [ref=e464]: 2 个项目
                  - img [ref=e466]
                - region "未分组 2 个项目" [ref=e468]:
                  - generic [ref=e471]:
                    - table [ref=e473]:
                      - rowgroup [ref=e485]:
                        - row "Select all rows 项目名称 分支 远程地址 状态 来源 图谱状态 向量状态 最近提交 操作" [ref=e486]:
                          - columnheader "Select all rows" [ref=e487]:
                            - generic "Select all rows" [ref=e489] [cursor=pointer]:
                              - generic [ref=e490]:
                                - checkbox "Select all rows"
                          - columnheader "项目名称" [ref=e492]:
                            - generic [ref=e493]: 项目名称
                          - columnheader "分支" [ref=e494]:
                            - generic [ref=e495]: 分支
                          - columnheader "远程地址" [ref=e496]:
                            - generic [ref=e497]: 远程地址
                          - columnheader "状态" [ref=e498]:
                            - generic [ref=e499]: 状态
                          - columnheader "来源" [ref=e500]:
                            - generic [ref=e501]: 来源
                          - columnheader "图谱状态" [ref=e502]:
                            - generic [ref=e503]: 图谱状态
                          - columnheader "向量状态" [ref=e504]:
                            - generic [ref=e505]: 向量状态
                          - columnheader "最近提交" [ref=e506]:
                            - generic [ref=e507]: 最近提交
                          - columnheader "操作" [ref=e508]:
                            - generic [ref=e509]: 操作
                    - table [ref=e514]:
                      - rowgroup [ref=e526]:
                        - 'row "Select this row demo-django master - Clean 扫描 未生成知识图谱 未生成 已完成 26/26 chore: initial Django demo for KG scanner coverage 选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e527]':
                          - cell "Select this row" [ref=e528]:
                            - generic "Select this row" [ref=e530] [cursor=pointer]:
                              - generic [ref=e531]:
                                - checkbox "Select this row"
                          - cell "demo-django" [ref=e533]:
                            - generic [ref=e536]: demo-django
                          - cell "master" [ref=e537]:
                            - generic [ref=e538]: master
                          - cell "-" [ref=e539]:
                            - generic [ref=e540]: "-"
                          - cell "Clean" [ref=e541]:
                            - generic [ref=e544]: Clean
                          - cell "扫描" [ref=e545]:
                            - generic [ref=e548]: 扫描
                          - cell "未生成知识图谱 未生成" [ref=e549]:
                            - generic [ref=e551]:
                              - generic "未生成知识图谱" [ref=e552]
                              - generic [ref=e553]: 未生成
                          - cell "已完成 26/26" [ref=e554]:
                            - generic [ref=e556]:
                              - generic [ref=e557]:
                                - 'generic "向量已生成 处理方法数: 26 耗时: 14.0s" [ref=e558]'
                                - generic [ref=e559]: 已完成
                              - generic [ref=e560]: 26/26
                          - 'cell "chore: initial Django demo for KG scanner coverage" [ref=e561]':
                            - generic [ref=e562]: "chore: initial Django demo for KG scanner coverage"
                          - cell "选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e563]:
                            - generic [ref=e564]:
                              - button "选择" [ref=e565] [cursor=pointer]:
                                - generic [ref=e566]:
                                  - img [ref=e568]
                                  - text: 选择
                              - button "提交分析" [ref=e570] [cursor=pointer]:
                                - generic [ref=e571]:
                                  - img [ref=e573]
                                  - text: 提交分析
                              - button "生成图谱" [ref=e575] [cursor=pointer]:
                                - generic [ref=e576]:
                                  - img [ref=e578]
                                  - text: 生成图谱
                              - button "描述&向量" [ref=e580] [cursor=pointer]:
                                - generic [ref=e581]:
                                  - img [ref=e583]
                                  - text: 描述&向量
                              - button "Git 操作" [ref=e587] [cursor=pointer]:
                                - generic [ref=e588]:
                                  - img [ref=e590]
                                  - text: Git 操作
                              - button "拉取" [ref=e592] [cursor=pointer]:
                                - generic [ref=e593]: 拉取
                              - button "图谱刷新" [ref=e594] [cursor=pointer]:
                                - generic [ref=e595]:
                                  - img [ref=e597]
                                  - text: 图谱刷新
                              - button "删除" [ref=e599] [cursor=pointer]:
                                - generic [ref=e600]: 删除
                        - 'row "Select this row demo-fastapi master - Clean 扫描 未生成知识图谱 未生成 已完成 37/37 chore: initial FastAPI demo for KG scanner coverage 选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e601]':
                          - cell "Select this row" [ref=e602]:
                            - generic "Select this row" [ref=e604] [cursor=pointer]:
                              - generic [ref=e605]:
                                - checkbox "Select this row"
                          - cell "demo-fastapi" [ref=e607]:
                            - generic [ref=e610]: demo-fastapi
                          - cell "master" [ref=e611]:
                            - generic [ref=e612]: master
                          - cell "-" [ref=e613]:
                            - generic [ref=e614]: "-"
                          - cell "Clean" [ref=e615]:
                            - generic [ref=e618]: Clean
                          - cell "扫描" [ref=e619]:
                            - generic [ref=e622]: 扫描
                          - cell "未生成知识图谱 未生成" [ref=e623]:
                            - generic [ref=e625]:
                              - generic "未生成知识图谱" [ref=e626]
                              - generic [ref=e627]: 未生成
                          - cell "已完成 37/37" [ref=e628]:
                            - generic [ref=e630]:
                              - generic [ref=e631]:
                                - 'generic "向量已生成 处理方法数: 37 耗时: 527.0s" [ref=e632]'
                                - generic [ref=e633]: 已完成
                              - generic [ref=e634]: 37/37
                          - 'cell "chore: initial FastAPI demo for KG scanner coverage" [ref=e635]':
                            - generic [ref=e636]: "chore: initial FastAPI demo for KG scanner coverage"
                          - cell "选择 提交分析 生成图谱 描述&向量 Git 操作 拉取 图谱刷新 删除" [ref=e637]:
                            - generic [ref=e638]:
                              - button "选择" [ref=e639] [cursor=pointer]:
                                - generic [ref=e640]:
                                  - img [ref=e642]
                                  - text: 选择
                              - button "提交分析" [ref=e644] [cursor=pointer]:
                                - generic [ref=e645]:
                                  - img [ref=e647]
                                  - text: 提交分析
                              - button "生成图谱" [ref=e649] [cursor=pointer]:
                                - generic [ref=e650]:
                                  - img [ref=e652]
                                  - text: 生成图谱
                              - button "描述&向量" [ref=e654] [cursor=pointer]:
                                - generic [ref=e655]:
                                  - img [ref=e657]
                                  - text: 描述&向量
                              - button "Git 操作" [ref=e661] [cursor=pointer]:
                                - generic [ref=e662]:
                                  - img [ref=e664]
                                  - text: Git 操作
                              - button "拉取" [ref=e666] [cursor=pointer]:
                                - generic [ref=e667]: 拉取
                              - button "图谱刷新" [ref=e668] [cursor=pointer]:
                                - generic [ref=e669]:
                                  - img [ref=e671]
                                  - text: 图谱刷新
                              - button "删除" [ref=e673] [cursor=pointer]:
                                - generic [ref=e674]: 删除
```

# Test source

```ts
  1   | import { test, expect } from '@playwright/test'
  2   | 
  3   | /**
  4   |  * RAM (需求分析大师) 菜单测试
  5   |  * 验证菜单项可点击并能正确导航
  6   |  */
  7   | 
  8   | // 使用process.cwd()获取当前工作目录
  9   | const SCREENSHOT_DIR = process.cwd() + '/test-results'
  10  | 
  11  | test.describe('需求分析大师菜单测试', () => {
  12  |   test.slow() // 增加超时时间
  13  | 
  14  |   test.beforeEach(async ({ page }) => {
  15  |     // 导航到首页
  16  |     await page.goto('/')
> 17  |     await page.waitForLoadState('networkidle')
      |                ^ TimeoutError: page.waitForLoadState: Timeout 30000ms exceeded.
  18  |   })
  19  | 
  20  |   test('需求分析大师菜单可点击并导航到/ram', async ({ page }) => {
  21  |     // 步骤1: 等待页面初始化
  22  |     await page.waitForTimeout(1000)
  23  | 
  24  |     // 检查是否有登录对话框弹出
  25  |     const loginDialog = page.locator('.el-dialog:visible').filter({ hasText: '登录' })
  26  |     const dialogCount = await loginDialog.count()
  27  | 
  28  |     if (dialogCount > 0) {
  29  |       // 填写登录表单
  30  |       const usernameInput = page.locator('.el-dialog input[placeholder="请输入用户名"]').first()
  31  |       const passwordInput = page.locator('.el-dialog input[placeholder="请输入密码"]').first()
  32  | 
  33  |       await usernameInput.fill('root')
  34  |       await passwordInput.fill('123456')
  35  | 
  36  |       // 点击登录按钮
  37  |       await page.locator('.el-dialog button:has-text("登录")').first().click()
  38  | 
  39  |       // 等待登录成功，对话框关闭
  40  |       await expect(loginDialog).not.toBeVisible({ timeout: 10000 })
  41  | 
  42  |       // 等待页面状态稳定
  43  |       await page.waitForLoadState('networkidle')
  44  |       await page.waitForTimeout(1000)
  45  |     }
  46  | 
  47  |     // 截图：登录后状态
  48  |     await page.screenshot({ path: `${SCREENSHOT_DIR}/ram-01-after-login.png`, fullPage: false })
  49  | 
  50  |     // 步骤2: 定位"需求分析大师"菜单项
  51  |     const ramMenuItem = page.locator('.el-menu-item').filter({ hasText: '需求分析大师' })
  52  | 
  53  |     // 步骤3: 验证菜单项存在且不是disabled状态
  54  |     await expect(ramMenuItem).toBeVisible({ timeout: 5000 })
  55  | 
  56  |     // 检查菜单项不是disabled状态（通过检查class）
  57  |     const isDisabled = await ramMenuItem.evaluate((el) => {
  58  |       return el.classList.contains('is-disabled')
  59  |     })
  60  |     expect(isDisabled).toBe(false)
  61  | 
  62  |     // 截图：点击前的菜单状态
  63  |     await page.screenshot({ path: `${SCREENSHOT_DIR}/ram-02-menu-before-click.png`, fullPage: false })
  64  | 
  65  |     // 步骤4: 点击菜单项
  66  |     await ramMenuItem.click({ force: true })
  67  | 
  68  |     // 步骤5: 验证URL跳转到 /ram
  69  |     await expect(page).toHaveURL(/\/ram/, { timeout: 10000 })
  70  | 
  71  |     // 截图：点击后的页面
  72  |     await page.screenshot({ path: `${SCREENSHOT_DIR}/ram-03-page-after-navigation.png`, fullPage: false })
  73  | 
  74  |     // 验证页面内容正确加载 - 使用宽松匹配并取第一个匹配项
  75  |     await expect(page.locator('text=/需求分析大师/').first()).toBeVisible({ timeout: 5000 })
  76  |   })
  77  | 
  78  |   test('需求分析大师菜单状态验证', async ({ page }) => {
  79  |     // 登录流程
  80  |     await page.waitForTimeout(1000)
  81  | 
  82  |     const loginDialog = page.locator('.el-dialog:visible').filter({ hasText: '登录' })
  83  |     const dialogCount = await loginDialog.count()
  84  | 
  85  |     if (dialogCount > 0) {
  86  |       await page.locator('.el-dialog input[placeholder="请输入用户名"]').first().fill('root')
  87  |       await page.locator('.el-dialog input[placeholder="请输入密码"]').first().fill('123456')
  88  |       await page.locator('.el-dialog button:has-text("登录")').first().click()
  89  |       await expect(loginDialog).not.toBeVisible({ timeout: 10000 })
  90  |       await page.waitForLoadState('networkidle')
  91  |     }
  92  | 
  93  |     // 验证菜单项属性
  94  |     const ramMenuItem = page.locator('.el-menu-item').filter({ hasText: '需求分析大师' })
  95  | 
  96  |     // 检查菜单项可见
  97  |     await expect(ramMenuItem).toBeVisible()
  98  | 
  99  |     // 检查菜单项是否可交互
  100 |     const isEnabled = await ramMenuItem.isEnabled()
  101 |     expect(isEnabled).toBe(true)
  102 | 
  103 |     // 检查菜单项没有被禁用的样式
  104 |     const opacity = await ramMenuItem.evaluate((el) => {
  105 |       return parseFloat(window.getComputedStyle(el).opacity)
  106 |     })
  107 |     expect(opacity).toBeGreaterThan(0.5) // disabled状态opacity为0.5
  108 | 
  109 |     // 检查菜单项颜色
  110 |     const color = await ramMenuItem.evaluate((el) => {
  111 |       return window.getComputedStyle(el).color
  112 |     })
  113 |     console.log('Menu item color:', color)
  114 | 
  115 |     // 截图
  116 |     await page.screenshot({ path: `${SCREENSHOT_DIR}/ram-04-menu-state-verification.png`, fullPage: false })
  117 |   })
```