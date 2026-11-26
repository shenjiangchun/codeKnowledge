package com.huawei.hisi.archdiagnosis.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源码注入点扫描服务单测：直接扫本地 .java 源码，区分接口注入 / 实现类注入 / 外部类型注入。
 *
 * <p>图谱构建会把接口注入层淡化为直达实现类的 CALLS 边；本服务从源码声明类型恢复该语义，
 * 供 AI 深度分析灰度验证「直接依赖实现类」类误报。</p>
 */
class ArchSourceScanServiceTest {

    private final ArchSourceScanService service = new ArchSourceScanService();

    @Test
    @DisplayName("统计摘要：接口注入 / 实现类注入 / 外部类型注入 各计 1")
    void summary_distinguishesInterfaceImplExternal(@TempDir Path root) throws IOException {
        write(root, "com/example/service/UserService.java",
                "package com.example.service;\npublic interface UserService { void getUser(); }\n");
        write(root, "com/example/service/UserServiceImpl.java",
                "package com.example.service;\npublic class UserServiceImpl implements UserService { public void getUser() {} }\n");
        write(root, "com/example/controller/UserController.java",
                "package com.example.controller;\n" +
                "import com.example.service.UserService;\n" +
                "import com.example.service.UserServiceImpl;\n" +
                "import org.springframework.beans.factory.annotation.Autowired;\n" +
                "import org.springframework.web.client.RestTemplate;\n" +
                "public class UserController {\n" +
                "    @Autowired private UserService userService;\n" +
                "    @Autowired private UserServiceImpl userServiceImpl;\n" +
                "    @Autowired private RestTemplate restTemplate;\n" +
                "    private String name;\n" +
                "}\n");

        Map<String, Object> summary = service.injectionSummary(List.of(root.toString()));

        assertThat(summary.get("interfaceInjectionCount")).isEqualTo(1);
        assertThat(summary.get("classInjectionCount")).isEqualTo(1);
        assertThat(summary.get("externalInjectionCount")).isEqualTo(1);
        assertThat(summary.get("classesWithInjection")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) summary.get("topImplInjectedClasses");
        assertThat(top).hasSize(1);
        assertThat(top.get(0).get("className")).isEqualTo("com.example.controller.UserController");
        assertThat((List<String>) top.get(0).get("implTypes")).contains("com.example.service.UserServiceImpl");
    }

    @Test
    @DisplayName("构造器注入：单有参构造器（无注解）视为构造注入，声明接口类型识别为 interface")
    void constructorInjection_singleArgCtor(@TempDir Path root) throws IOException {
        write(root, "com/example/service/OrderService.java",
                "package com.example.service;\npublic interface OrderService { void place(); }\n");
        write(root, "com/example/service/OrderServiceImpl.java",
                "package com.example.service;\npublic class OrderServiceImpl implements OrderService { public void place() {} }\n");
        write(root, "com/example/controller/OrderController.java",
                "package com.example.controller;\n" +
                "import com.example.service.OrderService;\n" +
                "public class OrderController {\n" +
                "    private final OrderService orderService;\n" +
                "    public OrderController(OrderService orderService) { this.orderService = orderService; }\n" +
                "}\n");

        Map<String, Object> sites = service.injectionSites(List.of(root.toString()), "com.example.controller.OrderController", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) sites.get("items");
        assertThat(items).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> injections = (List<Map<String, Object>>) items.get(0).get("injections");
        assertThat(injections).hasSize(1);
        assertThat(injections.get(0).get("via")).isEqualTo("constructor");
        assertThat(injections.get(0).get("name")).isEqualTo("orderService");
        assertThat(injections.get(0).get("kind")).isEqualTo("interface");
        assertThat(injections.get(0).get("fqn")).isEqualTo("com.example.service.OrderService");
    }

    @Test
    @DisplayName("带 @Autowired 构造器：参数识别为构造注入")
    void constructorInjection_autowiredCtor(@TempDir Path root) throws IOException {
        write(root, "com/example/service/UserService.java",
                "package com.example.service;\npublic interface UserService { void getUser(); }\n");
        write(root, "com/example/service/UserServiceImpl.java",
                "package com.example.service;\npublic class UserServiceImpl implements UserService { public void getUser() {} }\n");
        write(root, "com/example/controller/CtorController.java",
                "package com.example.controller;\n" +
                "import com.example.service.UserService;\n" +
                "import org.springframework.beans.factory.annotation.Autowired;\n" +
                "public class CtorController {\n" +
                "    private final UserService userService;\n" +
                "    @Autowired\n" +
                "    public CtorController(UserService userService) { this.userService = userService; }\n" +
                "}\n");

        Map<String, Object> sites = service.injectionSites(List.of(root.toString()), "com.example.controller.CtorController", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) sites.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> injections = (List<Map<String, Object>>) items.get(0).get("injections");
        assertThat(injections).hasSize(1);
        assertThat(injections.get(0).get("via")).isEqualTo("constructor");
        assertThat(injections.get(0).get("kind")).isEqualTo("interface");
    }

    @Test
    @DisplayName("@Resource / @Inject 注解同样识别为字段注入")
    void recognizesResourceAndInjectAnnotations(@TempDir Path root) throws IOException {
        write(root, "com/example/service/UserService.java",
                "package com.example.service;\npublic interface UserService { void getUser(); }\n");
        write(root, "com/example/controller/AnnoController.java",
                "package com.example.controller;\n" +
                "import com.example.service.UserService;\n" +
                "import javax.annotation.Resource;\n" +
                "import javax.inject.Inject;\n" +
                "public class AnnoController {\n" +
                "    @Resource private UserService byResource;\n" +
                "    @Inject private UserService byInject;\n" +
                "}\n");

        Map<String, Object> sites = service.injectionSites(List.of(root.toString()), "com.example.controller.AnnoController", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) sites.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> injections = (List<Map<String, Object>>) items.get(0).get("injections");
        assertThat(injections).hasSize(2);
        assertThat(injections).extracting(m -> m.get("kind")).containsOnly("interface");
    }

    @Test
    @DisplayName("packageFilter：只返回匹配包前缀的类")
    void packageFilter_filtersByPackage(@TempDir Path root) throws IOException {
        write(root, "com/example/service/UserService.java",
                "package com.example.service;\npublic interface UserService { void getUser(); }\n");
        write(root, "com/example/controller/UserController.java",
                "package com.example.controller;\n" +
                "import com.example.service.UserService;\n" +
                "import org.springframework.beans.factory.annotation.Autowired;\n" +
                "public class UserController {\n" +
                "    @Autowired private UserService userService;\n" +
                "}\n");
        write(root, "com/example/other/OtherBean.java",
                "package com.example.other;\n" +
                "import com.example.service.UserService;\n" +
                "import org.springframework.beans.factory.annotation.Autowired;\n" +
                "public class OtherBean {\n" +
                "    @Autowired private UserService userService;\n" +
                "}\n");

        Map<String, Object> sites = service.injectionSites(List.of(root.toString()), null, "com.example.controller");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) sites.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("className")).isEqualTo("com.example.controller.UserController");
    }

    @Test
    @DisplayName("排除测试类（*Test）与非注入字段")
    void excludesTestClassesAndNonInjectedFields(@TempDir Path root) throws IOException {
        write(root, "com/example/service/UserService.java",
                "package com.example.service;\npublic interface UserService { void getUser(); }\n");
        write(root, "com/example/controller/UserControllerTest.java",
                "package com.example.controller;\n" +
                "import com.example.service.UserService;\n" +
                "import org.springframework.beans.factory.annotation.Autowired;\n" +
                "public class UserControllerTest {\n" +
                "    @Autowired private UserService userService;\n" +
                "}\n");

        Map<String, Object> summary = service.injectionSummary(List.of(root.toString()));

        assertThat(summary.get("classesWithInjection")).isEqualTo(0);
        assertThat(summary.get("interfaceInjectionCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("空项目：返回零统计，不抛异常")
    void emptyProject_returnsZeroStats(@TempDir Path root) throws IOException {
        write(root, "com/example/NoInjection.java",
                "package com.example;\npublic class NoInjection { private String name; }\n");

        Map<String, Object> summary = service.injectionSummary(List.of(root.toString()));

        assertThat(summary.get("interfaceInjectionCount")).isEqualTo(0);
        assertThat(summary.get("classInjectionCount")).isEqualTo(0);
        assertThat(summary.get("externalInjectionCount")).isEqualTo(0);
        assertThat(summary.get("classesWithInjection")).isEqualTo(0);
    }

    @Test
    @DisplayName("直接依赖实现类：summary 统计 class 注入并列出实现类")
    void summary_countsImplClassInjection(@TempDir Path root) throws IOException {
        write(root, "com/example/service/UserServiceImpl.java",
                "package com.example.service;\npublic class UserServiceImpl { public void getUser() {} }\n");
        write(root, "com/example/controller/DirectController.java",
                "package com.example.controller;\n" +
                "import com.example.service.UserServiceImpl;\n" +
                "import org.springframework.beans.factory.annotation.Autowired;\n" +
                "public class DirectController {\n" +
                "    @Autowired private UserServiceImpl userServiceImpl;\n" +
                "}\n");

        Map<String, Object> summary = service.injectionSummary(List.of(root.toString()));

        assertThat(summary.get("classInjectionCount")).isEqualTo(1);
        assertThat(summary.get("interfaceInjectionCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("反向查询：injectedType=实现类FQN 只返回直接依赖该实现类的注入点，接口FQN 返回接口注入")
    void injectedType_reverseQueryFindsDirectImplDependents(@TempDir Path root) throws IOException {
        write(root, "com/example/service/UserService.java",
                "package com.example.service;\npublic interface UserService { void getUser(); }\n");
        write(root, "com/example/service/UserServiceImpl.java",
                "package com.example.service;\npublic class UserServiceImpl implements UserService { public void getUser() {} }\n");
        write(root, "com/example/controller/IfaceController.java",
                "package com.example.controller;\n" +
                "import com.example.service.UserService;\n" +
                "import org.springframework.beans.factory.annotation.Autowired;\n" +
                "public class IfaceController {\n" +
                "    @Autowired private UserService userService;\n" +
                "}\n");
        write(root, "com/example/controller/ImplController.java",
                "package com.example.controller;\n" +
                "import com.example.service.UserServiceImpl;\n" +
                "import org.springframework.beans.factory.annotation.Autowired;\n" +
                "public class ImplController {\n" +
                "    @Autowired private UserServiceImpl userServiceImpl;\n" +
                "}\n");

        Map<String, Object> impl = service.injectionSitesByInjectedType(
                List.of(root.toString()), "com.example.service.UserServiceImpl");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> implItems = (List<Map<String, Object>>) impl.get("items");
        assertThat(implItems).hasSize(1);
        assertThat(implItems.get(0).get("className")).isEqualTo("com.example.controller.ImplController");
        assertThat(implItems.get(0).get("kind")).isEqualTo("class");
        assertThat(impl.get("scannedFileCount")).isEqualTo(4);

        Map<String, Object> iface = service.injectionSitesByInjectedType(
                List.of(root.toString()), "com.example.service.UserService");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ifaceItems = (List<Map<String, Object>>) iface.get("items");
        assertThat(ifaceItems).hasSize(1);
        assertThat(ifaceItems.get(0).get("className")).isEqualTo("com.example.controller.IfaceController");
        assertThat(ifaceItems.get(0).get("kind")).isEqualTo("interface");
    }

    @Test
    @DisplayName("grepSource：按关键字命中源码行，返回 filePath/lineNumber/line 与扫描计数")
    void grepSource_findsMatchingLines(@TempDir Path root) throws IOException {
        write(root, "com/example/service/UserService.java",
                "package com.example.service;\npublic interface UserService { void getUser(); }\n");
        write(root, "com/example/controller/UserController.java",
                "package com.example.controller;\n" +
                "import com.example.service.UserService;\n" +
                "import org.springframework.beans.factory.annotation.Autowired;\n" +
                "public class UserController {\n" +
                "    @Autowired private UserService userService;\n" +
                "}\n");

        Map<String, Object> result = service.grepSource(List.of(root.toString()), "UserService", 30);

        assertThat(result.get("scannedFileCount")).isEqualTo(2);
        assertThat((Integer) result.get("total")).isGreaterThanOrEqualTo(2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) result.get("matches");
        assertThat(matches).isNotEmpty();
        assertThat(matches).anyMatch(m -> m.get("line").toString().contains("@Autowired"));
    }

    private static void write(Path root, String relPath, String content) throws IOException {
        Path file = root.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
