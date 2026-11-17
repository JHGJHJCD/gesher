"""Complete, bounded job inventories with smaller GitHub response pages."""
from github_read import GitHubReadError, get_json


def get_jobs(endpoint):
    """Read at most 100 jobs without accepting a partial or changing inventory.

    GitHub can return 502 for a 100-row attempt inventory while both 50-row
    pages succeed. Paging changes transport size, never producer acceptance.
    """
    total = None
    rows = []
    seen = set()
    for page in (1, 2):
        value = get_json(f'{endpoint}?per_page=50&page={page}')
        count = value.get('total_count') if isinstance(value, dict) else None
        items = value.get('jobs') if isinstance(value, dict) else None
        if (type(count) is not int or not 0 <= count <= 100
                or not isinstance(items, list) or len(items) > 50):
            raise GitHubReadError('Invalid or oversized job inventory')
        if total is not None and count != total:
            raise GitHubReadError('Job inventory changed between pages')
        total = count
        for job in items:
            job_id = job.get('id') if isinstance(job, dict) else None
            if type(job_id) is not int or job_id <= 0 or job_id in seen:
                raise GitHubReadError('Invalid or repeated job identity')
            seen.add(job_id)
        rows.extend(items)
        if len(rows) != min(50 * page, total):
            raise GitHubReadError('Incomplete or oversized job page')
        if len(rows) == total:
            return {'total_count': total, 'jobs': rows}
    raise GitHubReadError('Incomplete job inventory')
