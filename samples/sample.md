![MD Edit banner](images/banner.png "MD Edit")

# Project Phoenix: Status Report

*Prepared by the platform team, week 45*

Welcome to the **MD Edit** sample document. It shows how a real-world
document looks in the live preview and when printed. Try **File > Print
Preview**, or **File > Export as HTML** to get a standalone web page.

> **Summary:** the migration is ~~behind~~ back on schedule. Two of three
> milestones are complete and the remaining risk is *low*.

## Table of contents

1. [Highlights](#highlights)
2. [Milestones](#milestones)
3. [Metrics](#metrics)
4. [Technical notes](#technical-notes)
5. [Next steps](#next-steps)

---

## Highlights

- **Performance:** median response time dropped from 420 ms to 180 ms.
- **Reliability:** zero unplanned outages since the cut-over.
- **Cost:** monthly hosting bill reduced by about 30 %.
  - Compute: right-sized instances
  - Storage: moved cold data to archive tier
- **Developer experience:** new `make dev` target starts the whole stack.

## Milestones

- [x] Inventory existing services
- [x] Migrate the data layer
- [ ] Switch remaining traffic
- [ ] Decommission old cluster

### Timeline

| Phase | Owner | Start | End | Status |
|:------|:------|:-----:|:---:|-------:|
| Discovery | Alice | Jan 08 | Feb 02 | **Done** |
| Data migration | Bob | Feb 05 | Apr 26 | **Done** |
| Traffic switch | Carol | May 06 | Jun 14 | *In progress* |
| Decommission | Dave | Jun 17 | Jul 05 | Planned |

## Metrics

The numbers below come from the production dashboards.

| Metric | Before | After | Change |
|--------|-------:|------:|-------:|
| p50 latency | 420 ms | 180 ms | −57 % |
| p99 latency | 2.1 s | 0.9 s | −57 % |
| Error rate | 0.40 % | 0.05 % | −88 % |
| Deploys / week | 3 | 14 | +367 % |

Formula used for the change column: `(after - before) / before × 100`.

## Technical notes

### Configuration

The new services read their settings from environment variables. A minimal
local setup looks like this:

```bash
export PHOENIX_DB_URL="postgres://localhost:5432/phoenix"
export PHOENIX_CACHE_TTL=300
./gradlew run
```

### Retry logic

Requests to the legacy API are retried with exponential back-off:

```java
int attempt = 0;
while (true) {
    try {
        return client.send(request);
    } catch (IOException e) {
        if (++attempt >= MAX_ATTEMPTS) {
            throw e;
        }
        Thread.sleep((long) Math.pow(2, attempt) * 100);
    }
}
```

> **Note**
>
> The back-off is capped at 30 seconds. See the [design doc][design] for the
> reasoning behind these values.
>
> > "Retries without back-off are a denial-of-service attack you run against
> > yourself." — an on-call engineer, 3 a.m.

### Known issues

1. Report exports larger than 50 MB time out.
   Workaround: split the export by month.
2. The `user_profile_settings` table still has a few `NULL` time zones.
3. Some old links use `http://`; they redirect, but should be updated.

## Next steps

1. Finish the traffic switch for the **EU** region.
2. Run the load test again:
   ```
   k6 run --vus 200 --duration 10m load-test.js
   ```
3. Schedule the decommission review with the security team.

Questions? Write to <platform-team@example.com> or visit
https://example.com/phoenix for the live dashboard.

---

<div align="center"><small>Generated with MD Edit &middot; &copy; 2026 Example Corp.</small></div>

[design]: https://example.com/docs/phoenix-design "Phoenix design document"
