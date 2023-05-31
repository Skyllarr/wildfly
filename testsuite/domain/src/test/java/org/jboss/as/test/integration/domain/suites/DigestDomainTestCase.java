package org.jboss.as.test.integration.domain.suites;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.HeaderElement;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.*;
import org.jboss.as.test.integration.domain.management.util.DomainTestSupport;
import org.jboss.as.test.shared.TestSuiteEnvironment;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.exporter.ExplodedExporter;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
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

public class DigestDomainTestCase {

    private static DomainTestSupport testSupport;
    private static WebArchive webArchive;
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
        OTHER_RUNNING_SERVER_ADDRESS.add(HOST, "slave");
        OTHER_RUNNING_SERVER_ADDRESS.add(SERVER, "other-two");
        OTHER_RUNNING_SERVER_ADDRESS.protect();
        OTHER_RUNNING_SERVER_GROUP_ADDRESS.add(HOST, "slave");
        OTHER_RUNNING_SERVER_GROUP_ADDRESS.add(SERVER, "other-two");
        OTHER_RUNNING_SERVER_GROUP_ADDRESS.add(DEPLOYMENT, TEST);
        OTHER_RUNNING_SERVER_GROUP_ADDRESS.protect();

    }

    @BeforeClass
    public static void setupDomainAndDeployWebApp() throws Exception {

        webArchive = ShrinkWrap.create(WebArchive.class, TEST);
        webArchive.addAsWebResource(Thread.currentThread().getContextClassLoader().getResource("helloWorld/index.html"), "index.html");
        webArchive.addAsWebInfResource("domain-session-digest/web.xml", "web.xml");

        tmpDir = new File("target/deployments/" + DeploymentManagementTestCase.class.getSimpleName());
        new File(tmpDir, "archives").mkdirs();
//        new File(tmpDir, "exploded").mkdirs();
        webArchive.as(ZipExporter.class).exportTo(new File(tmpDir, "archives/" + TEST), true);
//        webArchive.as(ExplodedExporter.class).exportExploded(new File(tmpDir, "exploded"));

        final DomainTestSupport.Configuration configuration;
        configuration = DomainTestSupport.Configuration.create(DeploymentManagementTestCase.class.getSimpleName(),
                "domain-configs/domain-session-digest.xml", "host-configs/host-primary.xml", "host-configs/host-secondary.xml");
        testSupport = DomainTestSupport.create(configuration);
        testSupport.start();

        deployWebApplicationToDomain();
    }

    @Test
    public void testHttpSessionDigestProperty() throws Exception {
        testDigestAuthenticationForTwoServers();
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

    private void testDigestAuthenticationForTwoServers() throws IOException, NoSuchAlgorithmException, URISyntaxException {
        HttpClient httpclient2 = HttpClients.createDefault();
        HttpGet httpGet = new HttpGet("http://localhost:8080/test/");
        HttpResponse response = httpclient2.execute(httpGet);
        Map<String, String> wwwAuth = Arrays
                .stream(response.getHeaders("WWW-Authenticate")[0]
                        .getElements())
                .collect(Collectors.toMap(HeaderElement::getName, HeaderElement::getValue));
        // the first call always fails with a 401 with a provided nonce, realm, etc.
        Assert.assertEquals(response.getStatusLine().getStatusCode(), 401);

        String realm = wwwAuth.get("Digest realm");
        String nonce = wwwAuth.get("nonce");
        String uri = "/test/";

        String responseDigest = computeDigest("/test/", nonce, "", "", "myUser", "myPassword", "MD5", realm, "", "GET");

        // create response with headers
        HttpGet response2 = new HttpGet("http://localhost:8080/test/");
        response2.setHeader("Authorization", "Digest " +
                "username=" + "\"myUser\",\n" +
                "realm=\"" + realm + "\",\n" +
                "nonce=\"" + nonce + "\",\n" +
                "uri=\"" + uri + "\",\n" +
                "algorithm=\"" + "MD5" + "\",\n" +
                "response=\"" + responseDigest +
                "\"");

        // try to send a response to the server that did not send a challenge which will result in 401 if nonce manager not persisted
        response2.setURI(new URI("http://localhost:8630/test/"));
        HttpResponse chc2 = httpclient2.execute(response2);
        Assert.assertEquals(200, chc2.getStatusLine().getStatusCode()); // passes on the other server
        response2.releaseConnection();

        // try to send a response to the server that not send a challenge which will result in 200
        response2.setURI(new URI("http://localhost:8080/test/"));
        HttpResponse chc = httpclient2.execute(response2);
        Assert.assertEquals(401, chc.getStatusLine().getStatusCode()); // 401 is returned because the same nonce cannot be used twice
        // take the nonce from the 401 and send it to server 1
        Map<String, String> wwwAuth2 = Arrays
                .stream(chc.getHeaders("WWW-Authenticate")[0]
                        .getElements())
                .collect(Collectors.toMap(HeaderElement::getName, HeaderElement::getValue));
        realm = wwwAuth2.get("Digest realm");
        nonce = wwwAuth2.get("nonce");
        uri = "/test/";
        String responseDigest3 = computeDigest("/test/", nonce, "", "", "myUser", "myPassword", "MD5", realm, "", "GET");

        response2.releaseConnection();
        HttpGet response3 = new HttpGet("http://localhost:8630/test/");
        response3.setHeader("Authorization", "Digest " +
                "username=" + "\"myUser\",\n" +
                "realm=\"" + realm + "\",\n" +
                "nonce=\"" + nonce + "\",\n" +
                "uri=\"" + uri + "\",\n" +
                "algorithm=\"" + "MD5" + "\",\n" +
                "response=\"" + responseDigest3 +
                "\"");

        response3.setURI(new URI("http://localhost:8630/test/"));
        HttpResponse chc3 = httpclient2.execute(response3);
        Assert.assertEquals(200, chc3.getStatusLine().getStatusCode()); // passes on the other server
        response3.releaseConnection();
    }

    private String computeDigest(String uri, String nonce, String cnonce, String nc, String username, String password, String algorithm, String realm, String qop, String method) throws NoSuchAlgorithmException, NoSuchAlgorithmException {
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
        return DigestUtils.md5Hex(value).toString();
    }

}
