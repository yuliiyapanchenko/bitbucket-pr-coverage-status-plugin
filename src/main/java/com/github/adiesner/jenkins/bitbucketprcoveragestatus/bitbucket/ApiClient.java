package com.github.adiesner.jenkins.bitbucketprcoveragestatus.bitbucket;

import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethodBase;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.params.HttpClientParams;
import org.apache.commons.lang.StringUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.codehaus.jackson.map.type.TypeFactory;
import org.codehaus.jackson.type.JavaType;
import org.codehaus.jackson.type.TypeReference;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.github.adiesner.jenkins.bitbucketprcoveragestatus.Message.SHIELDS_BADGE_COVERAGE_URL;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.stream.Collectors.toList;

/**
 * Created by nishio
 */
public class ApiClient {
    private static final Logger logger = Logger.getLogger(ApiClient.class.getName());
    private static final String V2_API_BASE_URL = "https://bitbucket.org/api/2.0/repositories/";
    private static final String V2_API_URL = "https://api.bitbucket.org/2.0";
    private String owner;
    private String repositoryName;
    private Credentials credentials;
    private String name;
    private HttpClientFactory factory;
    private String userUuid;

    static class HttpClientFactory {
        static final HttpClientFactory INSTANCE = new HttpClientFactory();
        private static final int DEFAULT_TIMEOUT = 60000;

        HttpClient getInstanceHttpClient() {
            HttpClient client = new HttpClient();

            HttpClientParams params = client.getParams();
            params.setConnectionManagerTimeout(DEFAULT_TIMEOUT);
            params.setSoTimeout(DEFAULT_TIMEOUT);

            if (getInstance() == null) {
                return client;
            }
            ProxyConfiguration proxy = getInstance().proxy;
            if (proxy == null) return client;

            logger.log(Level.FINE, "Jenkins proxy: {0}:{1}", new Object[]{proxy.name, proxy.port});
            client.getHostConfiguration().setProxy(proxy.name, proxy.port);
            String username = proxy.getUserName();
            String password = proxy.getPassword();

            // Consider it to be passed if username specified. Sufficient?
            if (username != null && !"".equals(username.trim())) {
                logger.log(Level.FINE, "Using proxy authentication (user={0})", username);
                client.getState().setProxyCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(username, password));
            }

            return client;
        }

        private Jenkins getInstance() {
            return Jenkins.getInstance();
        }
    }

    public <T extends HttpClientFactory> ApiClient(
            String username, String password,
            String owner, String repositoryName,
            String key, String name,
            T httpFactory
    ) {
        this.credentials = new UsernamePasswordCredentials(username, password);
        this.owner = owner;
        this.repositoryName = repositoryName;
        this.name = name;
        this.factory = httpFactory != null ? httpFactory : HttpClientFactory.INSTANCE;
        this.userUuid = getLoggedInUserUUID();
    }

    public List<CloudPullrequest> getPullRequests() {
        return getAllValues(v2("/pullrequests/"), 50, CloudPullrequest.class);
    }

    private List<CloudPullrequest.Comment> getPullRequestComments(String pullRequestId) {
        return getAllValues(v2("/pullrequests/" + pullRequestId + "/comments"), 100, CloudPullrequest.Comment.class);
    }

    public List<CloudPullrequest.Comment> findOwnPullRequestComments(String pullRequestId) {
        List<CloudPullrequest.Comment> pullRequestComments = getPullRequestComments(pullRequestId);
        return pullRequestComments.stream()
                .filter(comment -> nonNull(comment.getAuthor()))
                .filter(comment -> comment.getAuthor().getUuid().equalsIgnoreCase(userUuid))
                .collect(toList());
    }

    // global comments do not have a file path (and inline object), and file-level comments do not require a line number
    public void deletePreviousGlobalComments(String pullRequestId, List<CloudPullrequest.Comment> ownComments) {
        ownComments.stream()
                .filter(comment -> isNull(comment.getInline()))
                .filter(comment -> comment.getContent().contains(SHIELDS_BADGE_COVERAGE_URL))
                .forEach(comment -> deletePullRequestComment(pullRequestId, comment.getId().toString()));
    }

    public String getName() {
        return this.name;
    }

    private void deletePullRequestComment(String pullRequestId, String commentId) {
        delete(v2("/pullrequests/" + pullRequestId + "/comments/" + commentId));
    }

    private String getLoggedInUserUUID() {
        try {
            String response = get(V2_API_URL + "/user");
            logger.log(Level.FINE, "getLoggedInUserUUIDResponse: " + response);
            return parse(response, CloudPullrequest.Author.class).getUuid();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Invalid user info response.", e);
        }

        return StringUtils.EMPTY;
    }

    public CloudPullrequest.Comment postPullRequestComment(String pullRequestId, String content) {
        CloudPullrequest.Comment data = new CloudPullrequest.Comment(content);
        try {
            String response = post(v2("/pullrequests/" + pullRequestId + "/comments"), data);
            logger.log(Level.FINE, "postCommentResponse: " + response);
            return parse(response, new TypeReference<CloudPullrequest.Comment>() {
            });
        } catch (Exception e) {
            logger.log(Level.WARNING, "Invalid pull request comment response.", e);
        }
        return null;
    }

    private <T> List<T> getAllValues(String rootUrl, int pageLen, Class<T> cls) {
        List<T> values = new ArrayList<>();
        try {
            String url = rootUrl + "?pagelen=" + pageLen;
            do {
                final JavaType type = TypeFactory.defaultInstance().constructParametricType(AbstractPullrequest.Response.class, cls);
                final String body = get(url);
                logger.log(Level.FINE, "****Received****:\n" + body + "\n");
                AbstractPullrequest.Response<T> response = parse(body, type);
                values.addAll(response.getValues());
                url = response.getNext();
            } while (url != null);
        } catch (Exception e) {
            logger.log(Level.WARNING, "invalid response.", e);
        }
        return values;
    }

    private HttpClient getHttpClient() {
        return this.factory.getInstanceHttpClient();
    }

    private String v2(String path) {
        return v2(this.owner, this.repositoryName, path);
    }

    private String v2(String owner, String repositoryName, String path) {
        return V2_API_BASE_URL + owner + "/" + repositoryName + path;
    }

    private String get(String path) {
        return send(new GetMethod(path));
    }

    private String post(String path, Object data) {
        try {
            final String jsonStr = ApiClient.serializeObject(data);
            final StringRequestEntity entity = new StringRequestEntity(jsonStr, "application/json", "utf-8");
            PostMethod req = new PostMethod(path);
            req.setRequestEntity(entity);
            logger.log(Level.FINE, "SENDING:\n" + jsonStr + "\n");
            return send(req);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Not able to parse data to json", e);
        }
        return null;
    }

    // Public static JSON serializer, so we can test serialization
    public static String serializeObject(Object obj) throws java.io.IOException {
        return new ObjectMapper().
                setSerializationInclusion(JsonSerialize.Inclusion.NON_NULL).
                writeValueAsString(obj);
    }

    private void delete(String path) {
        send(new DeleteMethod(path));
    }

    private String send(HttpMethodBase req) {
        HttpClient client = getHttpClient();
        client.getState().setCredentials(AuthScope.ANY, credentials);
        client.getParams().setAuthenticationPreemptive(true);
        try {
            client.executeMethod(req);
            return req.getResponseBodyAsString();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to send request.", e);
        } finally {
            req.releaseConnection();
        }
        return null;
    }

    private <R> R parse(String response, Class<R> cls) throws IOException {
        return new ObjectMapper().readValue(response, cls);
    }

    private <R> R parse(String response, JavaType type) throws IOException {
        return new ObjectMapper().readValue(response, type);
    }

    private <R> R parse(String response, TypeReference<R> ref) throws IOException {
        return new ObjectMapper().readValue(response, ref);
    }
}
