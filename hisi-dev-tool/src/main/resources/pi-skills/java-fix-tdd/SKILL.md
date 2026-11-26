---
name: java-fix-tdd
description: 在独立 git worktree 里完成 Java 技术债修复的开发任务：按任务清单改代码、把 Spec 的测试用例落成自动化用例、编译到通过。使用 edit/write/bash 工具改代码时加载本技能。
---

# Java 修复开发（TDD 流程）

你在一个独立的 git worktree 里工作，工作目录就是 worktree 根。后端只允许你改这个目录里的文件。

## 工作顺序

1. 读任务清单涉及的文件，弄清现状再动手；需要调用关系时用图谱工具（kg_callees_tree / kg_root_entries / kg_method_detail）。
2. 按任务清单逐项实现。改动尽量小，只改实现任务必需的部分，不重写无关代码。
3. Spec 的测试用例是验收标准：把它们落成自动化用例（JUnit 5）。
4. 每完成一部分就编译验证，编译不过就修到通过；跑新增的测试类确认它们通过。

## 测试类的硬性规则

- 只通过被测类的公共方法验证（直接 new 被测类，或 MockMvcBuilders.standaloneSetup）。
- 不要用反射查找私有字段；不要假设类里有某个依赖字段；不要为了注入 mock 去改被测类的构造器或可见性。
- 不要臆造类和包——只用项目里实际存在的类型；不确定时先用 grep/find 找，再用 read 看。
- 只用项目里已有的测试依赖与版本。
- 测试类自身有问题（编译不过、setUp 报错、找不到字段）就修测试类，但断言要和 Spec 的通过标准保持一致，不要为了通过削弱断言。

## 边界

- 不要执行任何 git 写操作（add / commit / push / checkout / reset / stash / branch）——提交与推送由后端完成。可以用 git status、git diff 查看自己的改动。
- 不要启动服务，不要运行需要外部依赖（数据库、网络服务）的命令。
- 编译验证命令：mvn -q test-compile（Windows 下 mvn.cmd -q test-compile 亦可）。

全部完成后最后一条消息只输出一个 JSON 对象（不要代码围栏）：{"summary": "一句话说明做了什么", "tasks": [{"index": 1, "status": "done | skipped", "note": "说明"}], "compile": "passed | failed"}
