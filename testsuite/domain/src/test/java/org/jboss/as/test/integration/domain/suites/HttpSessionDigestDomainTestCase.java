/*
 *
 * Copyright 2023 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.jboss.as.test.integration.domain.suites;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.HeaderElement;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.jboss.as.test.integration.domain.management.util.DomainTestSupport;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CONTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ENABLED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER;
import static org.jboss.as.test.integration.domain.management.util.DomainTestSupport.validateResponse;

public class HttpSessionDigestDomainTestCase {

    private static DomainTestSupport testSupport;
    private static File tmpDir;
    private static final String TEST = "test.war";

    public static final String SERVER_GROUP = "server-group";
    private static final String REPLACEMENT = "test.war.v2";
    private static final ModelNode ROOT_ADDRESS = new ModelNode();
    private static final ModelNode ROOT_DEPLOYMENT_ADDRESS = new ModelNode();
    private static final ModelNode ROOT_REPLACEMENT_ADDRESS = new ModelNode();
    private static final ModelNode MAIN_SERVER_GROUP_ADDRESS = new ModelNode();
    private static final ModelNode MAIN_SERVER_GROUP_DEPLOYMENT_ADDRESS = new ModelNode();
    private static final ModelNode OTHER_SERVER_GROUP_ADDRESS = new ModelNode();
    private static final ModelNode OTHER_SERVER_GROUP_DEPLOYMENT_ADDRESS = new ModelNode();
    private static final ModelNode MAIN_RUNNING_SERVER_ADDRESS = new ModelNode();
    private static final ModelNode MAIN_RUNNING_SERVER_DEPLOYMENT_ADDRESS = new ModelNode();
    private static final ModelNode OTHER_RUNNING_SERVER_ADDRESS = new ModelNode();
    private static final ModelNode OTHER_RUNNING_SERVER_GROUP_ADDRESS = new ModelNode();

    static {
        ROOT_ADDRESS.setEmptyList();
        ROOT_ADDRESS.protect();
        ROOT_DEPLOYMENT_ADDRESS.add(DEPLOYMENT, TEST);
        ROOT_DEPLOYMENT_ADDRESS.protect();
        ROOT_REPLACEMENT_ADDRESS.add(DEPLOYMENT, REPLACEMENT);
        ROOT_REPLACEMENT_ADDRESS.protect();
        MAIN_SERVER_GROUP_ADDRESS.add(SERVER_GROUP, "main-server-group");
        MAIN_SERVER_GROUP_ADDRESS.protect();
        MAIN_SERVER_GROUP_DEPLOYMENT_ADDRESS.add(SERVER_GROUP, "main-server-group");
        MAIN_SERVER_GROUP_DEPLOYMENT_ADDRESS.add(DEPLOYMENT, TEST);
        MAIN_SERVER_GROUP_DEPLOYMENT_ADDRESS.protect();
        OTHER_SERVER_GROUP_ADDRESS.add(SERVER_GROUP, "other-server-group");
        OTHER_SERVER_GROUP_ADDRESS.protect();
        OTHER_SERVER_GROUP_DEPLOYMENT_ADDRESS.add(SERVER_GROUP, "other-server-group");
        OTHER_SERVER_GROUP_DEPLOYMENT_ADDRESS.add(DEPLOYMENT, TEST);
        OTHER_SERVER_GROUP_DEPLOYMENT_ADDRESS.protect();
        MAIN_RUNNING_SERVER_ADDRESS.add(HOST, "master");
        MAIN_RUNNING_SERVER_ADDRESS.add(SERVER, "main-one");
        MAIN_RUNNING_SERVER_ADDRESS.protect();
        MAIN_RUNNING_SERVER_DEPLOYMENT_ADDRESS.add(HOST, "master");
        MAIN_RUNNING_SERVER_DEPLOYMENT_ADDRESS.add(SERVER, "main-one");
        MAIN_RUNNING_SERVER_DEPLOYMENT_ADDRESS.add(DEPLOYMENT, TEST);
        MAIN_RUNNING_SERVER_DEPLOYMENT_ADDRESS.protect();
        OTHER_RUNNING_SERVER_ADDRESS.add(HOST, "primary");
        OTHER_RUNNING_SERVER_ADDRESS.add(SERVER, "other-two");
        OTHER_RUNNING_SERVER_ADDRESS.protect();
        OTHER_RUNNING_SERVER_GROUP_ADDRESS.add(HOST, "primary");
        OTHER_RUNNING_SERVER_GROUP_ADDRESS.add(SERVER, "other-two");
        OTHER_RUNNING_SERVER_GROUP_ADDRESS.add(DEPLOYMENT, TEST);
        OTHER_RUNNING_SERVER_GROUP_ADDRESS.protect();

    }

    @BeforeClass
    public static void setupDomainAndDeployWebApp() throws Exception {

        WebArchive webArchive = ShrinkWrap.create(WebArchive.class, TEST);
        webArchive.addAsWebResource(Thread.currentThread().getContextClassLoader().getResource("helloWorld/index.html"), "index.html");
        webArchive.addAsWebInfResource("domain-session-digest/web.xml", "web.xml");

        tmpDir = new File("target/deployments/" + DeploymentManagementTestCase.class.getSimpleName());
        new File(tmpDir, "archives").mkdirs();
        webArchive.as(ZipExporter.class).exportTo(new File(tmpDir, "archives/" + TEST), true);

        final DomainTestSupport.Configuration configuration;
        configuration = DomainTestSupport.Configuration.create(DeploymentManagementTestCase.class.getSimpleName(),
                "domain-configs/domain-session-digest.xml", "host-configs/host-primary.xml", "host-configs/host-secondary.xml");
        testSupport = DomainTestSupport.create(configuration);
        testSupport.start();

        deployWebApplicationToDomain();
    }

    @Test
    public void testHttpSessionDigestPropertyWithTwoServers() throws Exception {
        testDigestAuthenticationForTwoServers();
    }

    @Test
    public void testHttpSessionDigestSameNonceCannotBeUsedTwice() throws Exception {
        String server1 = "http://localhost:8080/test/";
        String server2 = "http://localhost:8630/test/";

        try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
            HttpGet httpFirstGetRequest = new HttpGet(server1);
            HttpResponse response = httpclient.execute(httpFirstGetRequest);
            Map<String, String> wwwAuth = Arrays.stream(response.getHeaders("WWW-Authenticate")[0].getElements())
                    .collect(Collectors.toMap(HeaderElement::getName, HeaderElement::getValue));
            String realm = wwwAuth.get("Digest realm");
            String nonce = wwwAuth.get("nonce");
            String uri = "/test/";

            // the first call always fails with a 401 and a requested nonce, realm, etc.
            Assert.assertEquals(response.getStatusLine().getStatusCode(), 401);
            httpFirstGetRequest.releaseConnection();

            // create response with headers
            HttpGet request = new HttpGet(server1);
            addAuthenticateHeader(request, realm, nonce, uri);

            // send a response to the server2 which did not send a challenge
            // the result is 200 because the nonce manager was configured to be persisted with "org.wildfly.security.http.session-digest" option
            request.setURI(new URI(server2));
            Assert.assertEquals(200, httpclient.execute(request).getStatusLine().getStatusCode());
            request.releaseConnection();

            // try to send a same response to the server1 that have sent a challenge
            // 401 is returned because the same nonce cannot be used twice
            request.setURI(new URI(server1));
            response = httpclient.execute(request);
            Assert.assertEquals(401, response.getStatusLine().getStatusCode());
            request.releaseConnection();
        }
    }

    private void testDigestAuthenticationForTwoServers() throws IOException, NoSuchAlgorithmException, URISyntaxException {
        String server1 = "http://localhost:8080/test/";
        String server2 = "http://localhost:8630/test/";

        try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
            HttpGet httpFirstGetRequest = new HttpGet(server1);
            HttpResponse response = httpclient.execute(httpFirstGetRequest);
            Map<String, String> wwwAuth = Arrays.stream(response.getHeaders("WWW-Authenticate")[0].getElements())
                    .collect(Collectors.toMap(HeaderElement::getName, HeaderElement::getValue));
            String realm = wwwAuth.get("Digest realm");
            String nonce = wwwAuth.get("nonce");
            String uri = "/test/";

            // the first call always fails with a 401 and a requested nonce, realm, etc.
            Assert.assertEquals(response.getStatusLine().getStatusCode(), 401);
            httpFirstGetRequest.releaseConnection();

            // create response with headers
            HttpGet request = new HttpGet(server1);
            addAuthenticateHeader(request, realm, nonce, uri);

            // send a response to the server2 which did not send a challenge
            // the result is 200 because the nonce manager was configured to be persisted with "org.wildfly.security.http.session-digest" option
            request.setURI(new URI(server2));
            Assert.assertEquals(200, httpclient.execute(request).getStatusLine().getStatusCode());
            request.releaseConnection();
        }
    }

    private void addAuthenticateHeader(HttpGet httpGetRequestWithAuthHeader, String realm, String nonce, String uri) throws NoSuchAlgorithmException {
        httpGetRequestWithAuthHeader.setHeader("Authorization", "Digest " +
                "username=" + "\"myUser\",\n" +
                "realm=\"" + realm + "\",\n" +
                "nonce=\"" + nonce + "\",\n" +
                "uri=\"" + uri + "\",\n" +
                "algorithm=\"" + "MD5" + "\",\n" +
                "response=\"" + computeDigest("/test/", nonce, "myUser", "myPassword", "MD5", realm, "GET") +
                "\"");
    }

    private static void deployWebApplicationToDomain() throws IOException {
        String url = new File(tmpDir, "archives/" + TEST).toURI().toURL().toString();
        ModelNode content = new ModelNode();
        content.get("url").set(url);
        ModelNode composite = createDeploymentOperation(content, MAIN_SERVER_GROUP_DEPLOYMENT_ADDRESS, OTHER_SERVER_GROUP_DEPLOYMENT_ADDRESS);
        executeOnMaster(composite);
    }

    private static ModelNode createDeploymentOperation(ModelNode content, ModelNode... serverGroupAddressses) {
        ModelNode composite = getEmptyOperation(COMPOSITE, ROOT_ADDRESS);
        ModelNode steps = composite.get(STEPS);
        ModelNode step1 = steps.add();
        step1.set(getEmptyOperation(ADD, ROOT_DEPLOYMENT_ADDRESS));
        step1.get(CONTENT).add(content);
        for (ModelNode serverGroup : serverGroupAddressses) {
            ModelNode sg = steps.add();
            sg.set(getEmptyOperation(ADD, serverGroup));
            sg.get(ENABLED).set(true);
        }

        return composite;
    }

    private static ModelNode getEmptyOperation(String operationName, ModelNode address) {
        ModelNode op = new ModelNode();
        op.get(OP).set(operationName);
        if (address != null) {
            op.get(OP_ADDR).set(address);
        } else {
            // Just establish the standard structure; caller can fill in address later
            op.get(OP_ADDR);
        }
        return op;
    }

    private static ModelNode executeOnMaster(ModelNode op) throws IOException {
        return validateResponse(testSupport.getDomainPrimaryLifecycleUtil().getDomainClient().execute(op));
    }

    private String computeDigest(String uri, String nonce, String username, String password, String algorithm, String realm, String method) throws NoSuchAlgorithmException, NoSuchAlgorithmException {
        String A1, HashA1, A2, HashA2;
        MessageDigest md = MessageDigest.getInstance(algorithm);
        A1 = username + ":" + realm + ":" + password;
        HashA1 = getMD5(A1);
        A2 = method + ":" + uri;
        HashA2 = getMD5(A2);
        String combo, finalHash;
        combo = HashA1 + ":" + nonce + ":" + HashA2;
        finalHash = DigestUtils.md5Hex(combo);
        return finalHash;
    }

    public String getMD5(String value) {
        return DigestUtils.md5Hex(value);
    }
}
