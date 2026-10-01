# GitLab

GitLab support in the app is still to come. What exists today is its Trending, measured ahead of time so the app can show it once GitLab accounts land.

## Trending

gitlab.com has no trending page any more: GitLab removed the Explore → Trending tab in GitLab 19.0. Its API has no trending order either. So the daily job that measures Codeberg's Trending ([CODEBERG.md](CODEBERG.md#trending)) measures gitlab.com's too, with the same code: one run a day, star counts compared over time, the same daily, weekly and monthly lists in the same `TrendingFile` format, published at `gitlab/trending.json` and `gitlab/state.json` on the same GitHub Pages site. Nothing reads them in the app yet.

Checked against gitlab.com on 2026-10-01:

| | Codeberg | gitlab.com |
|---|---|---|
| Ranking read | `/api/v1/repos/search?sort=stars`, 28 pages of 50 | `/api/v4/projects?order_by=star_count&sort=desc&simple=true`, up to 50 pages of 100 |
| Repositories tracked | top 1,400 (13 stars or more) | top 5,000 (11 stars or more) |
| Owner | `owner.login` | `namespace.full_path`, which can be nested (`group/subgroup`) |
| Language | in the ranking | not in the list: one `/projects/:id/languages` call per listed project, the largest share wins (at most 75 calls) |
| New forks (tie-breaker) | 2 pages of the newest forks | none: `/projects` can't list only forks, and about 5,000 projects are created a day |
| Anonymous limit | 2,000 requests per 10 minutes | 500 per minute (`throttle_unauthenticated_api`) |

The reading lives in `tools:trending` (`GitLabTrendingCrawler`) rather than in a forge module, since the phone doesn't measure GitLab. It moves to a `forge:gitlab` module when GitLab accounts arrive. Recording the history and building the lists are shared with Codeberg (`forge:forgejo`'s `trending` package).

**Expect short daily lists.** Stars move slowly on gitlab.com. In September 2026, the most any project gained in a day was 2 or 3 stars, and only 1 to 9 projects a day reached the 2-star floor (measured from [n-someya/trending-collector](https://github.com/n-someya/trending-collector)'s published data). Over a month the leaders gain 25 to 60 stars, so the weekly and monthly lists carry the page.

**One job, one deployment.** A Pages deployment replaces the whole site, so both forges are published together. If one forge can't be measured, yesterday's files for it are published again, and the other forge still gets its day. If a forge's history can't be downloaded at all (anything but a 404), the run stops and the site keeps the previous day.
