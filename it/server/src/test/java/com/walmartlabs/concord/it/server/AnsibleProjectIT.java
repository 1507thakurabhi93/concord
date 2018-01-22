package com.walmartlabs.concord.it.server;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 Wal-Mart Store, Inc.
 * -----
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
 * =====
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.project.InternalConstants;
import com.walmartlabs.concord.server.api.org.project.ProjectEntry;
import com.walmartlabs.concord.server.api.org.project.RepositoryEntry;
import com.walmartlabs.concord.server.api.process.ProcessEntry;
import com.walmartlabs.concord.server.api.process.ProcessResource;
import com.walmartlabs.concord.server.api.process.ProcessStatus;
import com.walmartlabs.concord.server.api.process.StartProcessResponse;
import com.walmartlabs.concord.server.api.project.ProjectResource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.walmartlabs.concord.it.common.ServerClient.*;
import static java.util.Collections.singletonMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * @see "docs/examples/ansible_project"
 */
public class AnsibleProjectIT extends AbstractServerIT {

    private MockGitSshServer gitServer;
    private int gitPort;

    @Before
    public void setUp() throws Exception {
        Path data = Paths.get(AnsibleProjectIT.class.getResource("ansibleproject/git").toURI());
        Path repo = GitUtils.createBareRepository(data);

        gitServer = new MockGitSshServer(0, repo.toAbsolutePath().toString());
        gitServer.start();

        gitPort = gitServer.getPort();
    }

    @After
    public void tearDown() throws Exception {
        gitServer.stop();
    }

    @Test
    public void test() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("request", resource("ansibleproject/request.json"));
        input.put("inventory", resource("ansibleproject/inventory.ini"));
        test(input);
    }

    @Test
    public void testInlineInventory() throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("request", resource("ansibleproject/requestInline.json"));
        test(input);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFailure() throws Exception {
        String orgName = "Default";

        // ---

        Map<String, Object> input = new HashMap<>();
        input.put("request", resource("ansibleproject/requestFailure.json"));

        // ---

        String templatePath = "file://" + ITConstants.DEPENDENCIES_DIR + "/ansible-template.jar";

        String projectName = "project_" + randomString();
        String repoSecretName = "repoSecret_" + randomString();
        String repoName = "repo_" + randomString();
        String repoUrl = String.format(ITConstants.GIT_SERVER_URL_PATTERN, gitPort);
        String entryPoint = URLEncoder.encode(projectName + ":" + repoName, "UTF-8");

        // ---

        generateKeyPair(orgName, repoSecretName, false, null);

        // ---

        RepositoryEntry repo = new RepositoryEntry(null, null, repoName, repoUrl, "master", null, null, repoSecretName, false);
        ProjectResource projectResource = proxy(ProjectResource.class);
        Map<String, Object> cfg = Collections.singletonMap(InternalConstants.Request.TEMPLATE_KEY, templatePath);
        projectResource.createOrUpdate(new ProjectEntry(null, projectName, null, null, null, singletonMap(repoName, repo), cfg, null, null, true));

        // ---

        StartProcessResponse spr = start(entryPoint, input);

        // ---

        ProcessResource processResource = proxy(ProcessResource.class);
        waitForStatus(processResource, spr.getInstanceId(), ProcessStatus.FAILED);

        // ---

        Response resp = processResource.downloadAttachment(spr.getInstanceId(), "ansible_stats.json");
        assertEquals(Status.OK.getStatusCode(), resp.getStatus());

        ObjectMapper om = new ObjectMapper();
        Map<String, Object> stats = om.readValue(resp.readEntity(InputStream.class), Map.class);
        resp.close();

        Collection<String> failures = (Collection<String>) stats.get("failures");
        assertNotNull(failures);
        assertEquals(1, failures.size());
        assertEquals("128.0.0.1", failures.iterator().next());
    }

    @SuppressWarnings("unchecked")
    public void test(Map<String, Object> input) throws Exception {
        String orgName = "Default";

        // ---

        String templatePath = "file://" + ITConstants.DEPENDENCIES_DIR + "/ansible-template.jar";

        String projectName = "project_" + randomString();
        String repoSecretName = "repoSecret_" + randomString();
        String repoName = "repo_" + randomString();
        String repoUrl = String.format(ITConstants.GIT_SERVER_URL_PATTERN, gitPort);
        String entryPoint = URLEncoder.encode(projectName + ":" + repoName, "UTF-8");

        // ---

        generateKeyPair(orgName, repoSecretName, false, null);

        // ---

        RepositoryEntry repo = new RepositoryEntry(null, null, repoName, repoUrl, "master", null, null, repoSecretName, false);
        ProjectResource projectResource = proxy(ProjectResource.class);

        Map<String, Object> cfg = Collections.singletonMap(InternalConstants.Request.TEMPLATE_KEY, templatePath);
        projectResource.createOrUpdate(new ProjectEntry(null, projectName, null, null, null, singletonMap(repoName, repo), cfg, null, null, true));

        // ---

        StartProcessResponse spr = start(entryPoint, input);

        // ---

        ProcessResource processResource = proxy(ProcessResource.class);
        ProcessEntry psr = waitForCompletion(processResource, spr.getInstanceId());

        // ---

        byte[] ab = getLog(psr.getLogFileName());
        assertLog(".*\"msg\":.*Hello, world.*", ab);

        // check if `force_color` is working
        assertLogAtLeast(".*\\[0;32m.*", 3, ab);

        // ---

        Response resp = processResource.downloadAttachment(spr.getInstanceId(), "ansible_stats.json");
        assertEquals(Status.OK.getStatusCode(), resp.getStatus());

        ObjectMapper om = new ObjectMapper();
        Map<String, Object> stats = om.readValue(resp.readEntity(InputStream.class), Map.class);
        resp.close();

        Collection<String> oks = (Collection<String>) stats.get("ok");
        assertNotNull(oks);
        assertEquals(1, oks.size());
        assertEquals("127.0.0.1", oks.iterator().next());
    }

    private static InputStream resource(String path) {
        return AnsibleProjectIT.class.getResourceAsStream(path);
    }
}
