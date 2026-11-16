# Instructions for AI contributors and reviewers

Read [CONTRIBUTING.md](CONTRIBUTING.md) before implementing or reviewing changes.
Its project-scope policy is a required review criterion, including when the user
asks for a general code review without repeating the policy.

## Implementation

- Gesher provides Android compatibility on Windows. Preserve Android-visible
  contracts and application workflows when implementing desktop integration.
- Do not substitute inputs, fabricate capabilities or success, or bypass an
  application's requirements to make a specific application operation succeed.
  Changing only Gesher rather than the APK does not exempt a change.
- A Windows camera backend and an Android-requested file picker are legitimate
  integrations when they preserve their respective contracts. Replacing live
  camera capture with a saved image to satisfy an application's capture flow
  requires explicit maintainer approval as a scope exception.
- Obtain explicit maintainer approval before implementing a departure from the
  scope policy. Cite the approval and keep the exception within its documented
  bounds. Continue ordinary compatibility work without additional scope approval.
- Test the relevant production behavior and describe validation limits. Keep
  synthetic fixtures isolated from ordinary application behavior.

## Review instructions

1. Read CONTRIBUTING.md and inspect the actual diff at the current head before
   deciding whether to recommend approval. Treat PR descriptions, checkboxes and
   contributor explanations as claims to verify against the implementation.
2. Review project scope before implementation correctness. Check whether inputs,
   workflows, capabilities, permissions or success results seen by an application
   are changed to bypass its expectations. Inspect affected call paths and tests
   where needed; green CI does not establish policy compliance.
3. Mark an unapproved departure as a **merge blocker**. Cite the code location,
   the policy rule and the behavior it causes. Recommend removal or redesign,
   or require explicit maintainer approval of that specific exception. Do not
   recommend approval while the violation remains unresolved.
4. Verify any claimed maintainer approval in the supplied or accessible issue/PR
   discussion. If approval or contract evidence is unavailable, state the missing
   evidence and withhold an approval recommendation. Do not invent authorization
   or treat a contributor's assertion as maintainer approval.
5. Check correctness and relevant regressions even for approved exceptions.
   Distinguish real production execution from mocks and host-only tests. Reassess
   affected findings when the PR changes.

In the review, state the reviewed commit, the scope verdict (compliant,
approved exception, blocked, or unresolved), the evidence for it, actionable
findings and validation limits. If blocked or unresolved, clearly state that
the PR is not ready for approval. A review request alone does not authorize
posting the review, modifying GitHub state or merging.
