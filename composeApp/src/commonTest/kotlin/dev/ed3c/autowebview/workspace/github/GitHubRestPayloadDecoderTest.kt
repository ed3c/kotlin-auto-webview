package dev.ed3c.autowebview.workspace.github

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubRestPayloadDecoderTest {
    private val decoder = GitHubRestPayloadDecoder()
    private val baseSha = "a".repeat(40)
    private val headSha = "b".repeat(40)
    private val treeSha = "c".repeat(40)

    @Test
    fun endpointRejectsNonOfficialHostsCredentialsQueriesAndPaths() {
        assertEquals("https://api.github.com", GitHubApiEndpoint().origin)
        assertFailsWith<IllegalArgumentException> { GitHubApiEndpoint("http://api.github.com") }
        assertFailsWith<IllegalArgumentException> { GitHubApiEndpoint("https://example.com") }
        assertFailsWith<IllegalArgumentException> {
            GitHubApiEndpoint("https://user:pass@api.github.com")
        }
        assertFailsWith<IllegalArgumentException> {
            GitHubApiEndpoint("https://api.github.com?token=forbidden")
        }
        assertFailsWith<IllegalArgumentException> {
            GitHubApiEndpoint("https://api.github.com/repos")
        }
    }

    @Test
    fun repositoryIssueAndPullRequestPayloadsMapToStableRestIds() {
        val slug = GitHubRepositorySlug("example", "public-repository")
        val repository = decoder.decodeRepository(
            payload = """
                {
                  "id": 100,
                  "full_name": "example/public-repository",
                  "private": false,
                  "default_branch": "main",
                  "archived": false,
                  "disabled": false,
                  "updated_at": "2026-08-20T00:00:00Z"
                }
            """.trimIndent(),
            expectedSlug = slug,
        )
        assertEquals(100, repository.repositoryId)
        assertEquals(GitHubRepositoryVisibility.PUBLIC, repository.visibility)

        val issue = decoder.decodeIssue(
            payload = """
                {
                  "id": 201,
                  "number": 7,
                  "title": "close exact mapping",
                  "state": "closed",
                  "state_reason": "completed",
                  "updated_at": "2026-08-20T01:00:00Z"
                }
            """.trimIndent(),
            repositoryId = repository.repositoryId,
        )
        assertEquals(201, issue.issueId)
        assertEquals(GitHubIssueState.CLOSED, issue.state)
        assertEquals(GitHubIssueStateReason.COMPLETED, issue.stateReason)

        val pullRequest = decoder.decodePullRequest(
            payload = """
                {
                  "id": 301,
                  "number": 8,
                  "title": "stacked draft",
                  "state": "open",
                  "draft": true,
                  "merged": false,
                  "base": {
                    "ref": "main",
                    "sha": "$baseSha",
                    "repo": {"id": 100}
                  },
                  "head": {
                    "ref": "feature",
                    "sha": "$headSha",
                    "repo": null
                  },
                  "merge_commit_sha": null,
                  "updated_at": "2026-08-20T02:00:00Z"
                }
            """.trimIndent(),
            repositoryId = repository.repositoryId,
        )
        assertEquals(301, pullRequest.pullRequestId)
        assertEquals(GitHubPullRequestState.OPEN, pullRequest.state)
        assertEquals(GitHubBranchRefState.DELETED, pullRequest.headRefState)
        assertEquals(headSha, pullRequest.headSha)
    }

    @Test
    fun pullRequestIssueAliasIsRejectedInsteadOfDoubleCounted() {
        assertFailsWith<IllegalArgumentException> {
            decoder.decodeIssue(
                payload = """
                    {
                      "id": 201,
                      "number": 7,
                      "title": "alias",
                      "state": "open",
                      "state_reason": null,
                      "updated_at": "2026-08-20T01:00:00Z",
                      "pull_request": {"url": "https://api.github.com/repos/example/public-repository/pulls/7"}
                    }
                """.trimIndent(),
                repositoryId = 100,
            )
        }
    }

    @Test
    fun commitResponseMustMatchRequestedSha() {
        val payload = """
            {
              "sha": "$headSha",
              "commit": {
                "author": {"date": "2026-08-20T00:00:00Z"},
                "committer": {"date": "2026-08-20T00:01:00Z"},
                "tree": {"sha": "$treeSha"}
              }
            }
        """.trimIndent()
        val commit = decoder.decodeCommit(payload, repositoryId = 100, expectedSha = headSha)
        assertEquals(headSha, commit.sha)
        assertEquals(treeSha, commit.treeSha)
        assertEquals("2026-08-20T00:01:00Z", commit.committedRevision)

        assertFailsWith<IllegalArgumentException> {
            decoder.decodeCommit(payload, repositoryId = 100, expectedSha = baseSha)
        }
    }

    @Test
    fun checkPageRejectsUnexpectedShaAndPreservesConclusion() {
        val payload = """
            {
              "total_count": 2,
              "check_runs": [
                {
                  "id": 401,
                  "name": "common-web-desktop",
                  "head_sha": "$headSha",
                  "status": "completed",
                  "conclusion": "success",
                  "started_at": "2026-08-20T00:00:00Z",
                  "completed_at": "2026-08-20T00:10:00Z"
                },
                {
                  "id": 402,
                  "name": "android",
                  "head_sha": "$headSha",
                  "status": "in_progress",
                  "conclusion": null,
                  "started_at": "2026-08-20T00:05:00Z",
                  "completed_at": null
                }
              ]
            }
        """.trimIndent()
        val decoded = decoder.decodeCheckRuns(payload, repositoryId = 100, expectedHeadSha = headSha)
        assertEquals(2, decoded.totalCount)
        assertEquals(GitHubCheckConclusion.SUCCESS, decoded.checkRuns[0].conclusion)
        assertEquals(GitHubCheckStatus.IN_PROGRESS, decoded.checkRuns[1].status)

        assertFailsWith<IllegalArgumentException> {
            decoder.decodeCheckRuns(payload, repositoryId = 100, expectedHeadSha = baseSha)
        }
    }

    @Test
    fun repositoryResponseCannotSilentlyChangeRequestedIdentity() {
        assertFailsWith<IllegalArgumentException> {
            decoder.decodeRepository(
                payload = """
                    {
                      "id": 100,
                      "full_name": "other/repository",
                      "private": false,
                      "default_branch": "main",
                      "updated_at": "2026-08-20T00:00:00Z"
                    }
                """.trimIndent(),
                expectedSlug = GitHubRepositorySlug("example", "public-repository"),
            )
        }
    }
}
