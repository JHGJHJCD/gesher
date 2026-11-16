## Change and purpose

Describe the problem and the behavior before and after this change.

## Android contract and project scope

Read [CONTRIBUTING.md](https://github.com/gesher-runtime/gesher/blob/main/CONTRIBUTING.md),
especially "Project scope: preserve application behavior".

- Which Android API or system contract does this implement or repair? Link the
  relevant Android documentation or AOSP source. For changes with no runtime
  behavior impact, explain why this is not applicable.
- Does it change application-visible inputs, workflows, permissions, capability
  reporting or success results? Explain how it preserves application behavior.
- Does it introduce synthetic inputs, device simulation or package-specific
  behavior? Explain the production/test boundary, or state that it does not.
- If it departs from project scope, link explicit maintainer approval of that
  specific exception. Otherwise, state "No scope exception".

## Validation

List the checks performed, their results, and what remains unverified. Identify
which checks exercise production code and which use mocks or host-only fixtures.

## Contributor checklist

- [ ] I have read the contribution policy and checked this change against it.
- [ ] I have explained application-visible behavior and linked any scope approval.
- [ ] I have reviewed the submitted code, including any AI-generated changes.
- [ ] I have identified third-party code and licenses, if any, and have the right to submit this contribution.

## Reviewer instructions

Read CONTRIBUTING.md and inspect the actual current-head diff. Check project
scope before correctness. A checked box or green CI is not proof of compliance.
Report unapproved workflow substitutions, bypasses or fabricated capabilities as
merge blockers. Withhold an approval recommendation when scope evidence or a
required maintainer approval is missing. State the reviewed commit, scope
conclusion, supporting evidence, blockers and validation limits in your review.
