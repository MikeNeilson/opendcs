import * as core from '@actions/core'
import * as github from '@actions/github'
import {Context} from '@actions/github/lib/context'

try
{
    const context: Context = github.context;
    const token: string = core.getInput('repo-token');
    const octokit: any = github.getOctokit(token);
    core.info("Hello from junit report");
    octokit.rest.issues.createComment(
        {
            issue_number: context.issue.number,
            owner: context.repo.owner,
            repo: context.repo.repo,
            body: 'Hello from action running on ' + process.platform

        }
    )
}
catch (error)
{
    if (error instanceof Error)
    {
        core.setFailed(error.message);
    }
}