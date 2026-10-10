package org.adaway.model.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.net.MalformedURLException;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collection;

/**
 * This class tests the git hosted source behavior.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
@RunWith(Parameterized.class)
public class GitHostsSourceTest {

    private static final String GITHUB_HOST = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final String GIST_HOST = "https://gist.githubusercontent.com/PerfectSlayer/a552900539d10271542063d67424b467/raw/56aabad791fbd085f4b9c5051a1dfa76b9a9d748/hosts";
    private static final String GITLAB_HOST = "https://gitlab.com/quidsup/notrack-blocklists/raw/master/notrack-blocklist.txt";

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {
                        "GitHub", GITHUB_HOST, GitHubHostsSource.class,
                        "https://api.github.com/repos/StevenBlack/hosts/commits?per_page=1&path=hosts",
                        "[{\"sha\":\"1\",\"commit\":{\"committer\":{\"name\":\"a\",\"date\":\"2026-10-01T12:34:56Z\"}}}]",
                        ZonedDateTime.parse("2026-10-01T12:34:56Z")
                },
                {
                        "Gist", GIST_HOST, GistHostsSource.class,
                        "https://api.github.com/gists/a552900539d10271542063d67424b467",
                        "{\"id\":\"a552900539d10271542063d67424b467\",\"updated_at\":\"2026-10-02T08:00:00Z\"}",
                        ZonedDateTime.parse("2026-10-02T08:00:00Z")
                },
                {
                        "GitLab", GITLAB_HOST, GitLabHostsSource.class,
                        "https://gitlab.com/api/v4/projects/quidsup%2Fnotrack-blocklists/repository/commits?path=notrack-blocklist.txt&ref_name=master",
                        "[{\"id\":\"1\",\"committed_date\":\"2026-10-03T09:15:00.000+02:00\"}]",
                        ZonedDateTime.parse("2026-10-03T09:15:00.000+02:00")
                }
        });
    }

    private final String label;
    private final String url;
    private final Class<?> expectedClass;
    private final String expectedApiUrl;
    private final String apiResponse;
    private final ZonedDateTime expectedLastUpdate;

    /**
     * Constructor.
     *
     * @param label              The Git hosting label.
     * @param url                A git hosted file URL.
     * @param expectedClass      The expected git hosting strategy.
     * @param expectedApiUrl     The address its last commit is asked for.
     * @param apiResponse        An answer of that API, as recorded.
     * @param expectedLastUpdate The date that answer gives.
     */
    public GitHostsSourceTest(String label, String url, Class<?> expectedClass, String expectedApiUrl,
                              String apiResponse, ZonedDateTime expectedLastUpdate) {
        this.label = label;
        this.url = url;
        this.expectedClass = expectedClass;
        this.expectedApiUrl = expectedApiUrl;
        this.apiResponse = apiResponse;
        this.expectedLastUpdate = expectedLastUpdate;
    }

    /**
     * Check the source is well detected as git hasted.
     */
    @Test
    public void testIsHostedOnGit() {
        assertTrue(
                "Git hosting for " + this.label + " was not detected",
                GitHostsSource.isHostedOnGit(this.url)
        );
    }

    /**
     * Check the last update date is asked to the right API and read from its answer.
     * <p>
     * The answer is a recorded one rather than asked over the network: the hosting services
     * limit or challenge requests from build servers, which made the test fail for reasons that
     * had nothing to do with the application.
     */
    @Test
    public void testLastUpdateFetch() throws MalformedURLException, JSONException {
        GitHostsSource source = GitHostsSource.getSource(this.url);
        assertTrue(
                "Invalid git hosting strategy for " + this.label,
                this.expectedClass.isInstance(source)
        );
        GitHostsJsonApiSource apiSource = (GitHostsJsonApiSource) source;
        assertEquals(this.expectedApiUrl, apiSource.getCommitApiUrl());
        assertEquals(this.expectedLastUpdate, apiSource.parseJsonBody(this.apiResponse));
    }

    /**
     * Check an answer without a usable date gives no date rather than failing.
     */
    @Test
    public void testLastUpdateMissing() throws MalformedURLException, JSONException {
        GitHostsJsonApiSource source = (GitHostsJsonApiSource) GitHostsSource.getSource(this.url);
        String withoutDate = this.apiResponse.replaceAll("\\d{4}-\\d{2}-\\d{2}T[^\"]*", "not a date");
        assertNull(source.parseJsonBody(withoutDate));
    }
}
