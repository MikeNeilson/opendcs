import * as core from '@actions/core'
import * as github from '@actions/github'
import { Context } from '@actions/github/lib/context.js';
import * as glob from '@actions/glob'


try
{
    
    const globber: glob.Globber = await glob.create('**/TEST-*.xml',{followSymbolicLinks:false})
    const files: string[] = await globber.glob();
    for(const file in files)
    {
        core.info("Will process: " + file[file]);
    }

    const context: Context = github.context;
    const token: string = core.getInput('repo-token');
    const reportName: String = core.getInput('name');
    const octokit: any = github.getOctokit(token);
    core.info("Hello from junit report");

    let reportText: String = "# " + reportName + "\n\n";
    reportText = reportText + 'Hello from action running on ' + process.platform;

    octokit.rest.issues.createComment(
        {
            issue_number: context.issue.number,
            owner: context.repo.owner,
            repo: context.repo.repo,
            body: reportText
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