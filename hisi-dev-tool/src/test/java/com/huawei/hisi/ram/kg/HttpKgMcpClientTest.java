package com.huawei.hisi.ram.kg;

import com.huawei.hisi.ram.kg.dto.Seed;
import com.huawei.hisi.ram.kg.impl.HttpKgMcpClient;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpKgMcpClientTest {

    private MockWebServer server;
    private HttpKgMcpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/mcp").toString();
        client = new HttpKgMcpClient(baseUrl, new OkHttpClient());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void hybridSearch_returnsSeeds() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"results\":[{\"nodeId\":\"n1\",\"score\":0.9,\"summary\":\"s\"}]}"));

        List<Seed> seeds = client.hybridSearch("user login", "/p", 5);

        assertThat(seeds).hasSize(1);
        assertThat(seeds.get(0).nodeId()).isEqualTo("n1");
        assertThat(seeds.get(0).score()).isEqualTo(0.9);
        assertThat(seeds.get(0).summary()).isEqualTo("s");
    }
}
