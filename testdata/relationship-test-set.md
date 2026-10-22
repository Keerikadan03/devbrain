# Relationship test set

Real articles chosen to exercise all 8 relationship types. Every URL below returned HTTP 200 to the
app's fetcher on 2026-10-22 (sites can change that at any time). Paste them one at a time into the
Add page with Auto-detect on, then compare the graph with the "Expect" column.

The "Expect" column is what a good classifier *should* produce, not a guarantee: the classifier
judges each new document only against candidates it finds, so ingestion order and wording matter.

## Set A — Indian politics: protests over the voter-list revision (SIR), October 2026

The same event seen from the ruling party, the opposition, the protest movement, the Election
Commission, Indian media, international media and reference pages. Good for **opposing view**,
**shared idea**, **same concept**, **supersedes** (later reports updating earlier ones),
**example of** and **builds upon**.

| # | Angle | URL |
|---|-------|-----|
| A1 | Background (reference) | https://en.wikipedia.org/wiki/Special_Intensive_Revision |
| A2 | Background: the protest party (CJP) | https://en.wikipedia.org/wiki/Cockroach_Janta_Party |
| A3 | Explainer: the CEC and the questions he faces | https://www.brut.media/in/articles/india/justice/who-is-gyanesh-kumar-why-the-cec-is-facing-questions-over-sir-voter-rolls-and-eci-decisions |
| A4 | BJP framing: "regime change plot", Rahul Gandhi "gaddar" (ThePrint) | https://theprint.in/india/bjp-cites-media-report-to-target-congs-regime-change-plot-terms-rahul-gandhi-gaddar/3078606/ |
| A5 | BJP framing, second outlet (Outlook) | https://www.outlookindia.com/national/bjp-calls-rahul-gandhi-gaddar-alleges-congress-links-to-foreign-groups-behind-protests |
| A6 | PM Modi: protests are needed but "Congress destroys them" | https://www.freepressjournal.in/india/protests-seeking-accountability-are-necessary-but-congress-destroys-them-says-pm-narendra-modi |
| A7 | Government vs UN experts on the crackdown | https://thelogicalindian.com/india-united-nations-delhi-protest-131180/ |
| A8 | Congress: "EC stands exposed for bias" | https://www.tribuneindia.com/news/india/cong-slams-ec-says-it-stands-exposed-for-bias-inefficiency |
| A9 | Opposition mulls impeachment of the CEC | https://www.tribuneindia.com/news/india/oppn-mulls-impeachment-motion-against-cec |
| A10 | INDIA bloc plans to revive the protest | https://www.nktv.in/india-bloc-prepares-to-revive-protest-demanding-gyanesh-kumars-resignation-leaders-to-meet-today/ |
| A11 | Protesters' side: mass detentions in Delhi (The Quint) | https://www.thequint.com/amp/story/news/politics/gyanesh-kumar-resignation-protest-cjp-delh-jantar-mantar-aisa-neha-bora |
| A12 | Analysis: Gen Z "cockroach" protesters and democracy (The Conversation) | https://theconversation.com/indian-gen-z-cockroach-protesters-aim-to-upend-assumptions-about-democracy-and-populism-in-modis-india-294730 |
| A13 | Protests spread to Hyderabad and Srinagar (Outlook photo story) | https://www.outlookindia.com/national/in-photos-gyanesh-kumar-resignation-protests-spread-from-delhi-to-hyderabad-srinagar |
| A14 | International: Oct 6, opposition leaders briefly arrested (Al Jazeera) | https://www.aljazeera.com/news/2026/10/6/india-opposition-leaders-briefly-arrested-in-protest-against-election-chief |
| A15 | International: Oct 10, CJP leaders detained, Delhi lockdown (Al Jazeera) | https://www.aljazeera.com/news/2026/10/10/indias-cockroach-leaders-detained-new-delhi-in-lockdown |
| A16 | International: Oct 19, court extends detention (Al Jazeera) | https://www.aljazeera.com/news/2026/10/19/indian-court-extends-detention-of-cockroach-leaders |
| A17 | International: Oct 12, march to the Supreme Court (NPR) | https://www.npr.org/2026/10/12/nx-s1-5998831/india-opposition-protests |

**Expect:**
- **opposing view** — BJP/PM pieces (A4–A6) vs Congress/opposition pieces (A8–A10); government (A7) vs protesters (A11).
- **same concept** — A4 ↔ A5 (two outlets reporting the same BJP attack).
- **supersedes** — A16 → A15 (the court ruling updates the detention story).
- **example of** — A11, A13, A14 → A1/A2 (specific protests as instances of the SIR protest movement).
- **builds upon** — A12 → A2 (analysis that builds on what the CJP is).
- **shared idea** — most cross-outlet pairs about the protests.

The Hindu, Indian Express, India Today, Hindustan Times and Scroll are reachable too (their section
pages returned 200), but I didn't find their articles on this story — add their article links if you
want more Indian outlets.

## Set B — Tech, to force the directional types

Politics rarely produces runtime dependencies or clean prerequisites, so this set targets them.

| # | Topic | URL |
|---|-------|-----|
| B1 | HTTP/2 | https://en.wikipedia.org/wiki/HTTP/2 |
| B2 | HTTP/3 | https://en.wikipedia.org/wiki/HTTP/3 |
| B3 | QUIC | https://en.wikipedia.org/wiki/QUIC |
| B4 | BERT | https://en.wikipedia.org/wiki/BERT_(language_model) |
| B5 | Raft consensus algorithm | https://en.wikipedia.org/wiki/Raft_(algorithm) |
| B6 | etcd | https://en.wikipedia.org/wiki/Etcd |
| B7 | Kubernetes | https://en.wikipedia.org/wiki/Kubernetes |
| B8 | PostgreSQL | https://en.wikipedia.org/wiki/PostgreSQL |
| B9 | MongoDB | https://en.wikipedia.org/wiki/MongoDB |

**Expect:**
- **supersedes** — HTTP/3 → HTTP/2.
- **depends on** — HTTP/3 → QUIC; Kubernetes → etcd.
- **builds upon** — BERT → "Attention Is All You Need" (the Transformer paper already in the knowledge base).
- **example of** — etcd → Raft (etcd implements Raft).
- **contrasts with** — PostgreSQL ↔ MongoDB.

## Plain list (for pasting)

```
https://en.wikipedia.org/wiki/Special_Intensive_Revision
https://en.wikipedia.org/wiki/Cockroach_Janta_Party
https://www.brut.media/in/articles/india/justice/who-is-gyanesh-kumar-why-the-cec-is-facing-questions-over-sir-voter-rolls-and-eci-decisions
https://theprint.in/india/bjp-cites-media-report-to-target-congs-regime-change-plot-terms-rahul-gandhi-gaddar/3078606/
https://www.outlookindia.com/national/bjp-calls-rahul-gandhi-gaddar-alleges-congress-links-to-foreign-groups-behind-protests
https://www.freepressjournal.in/india/protests-seeking-accountability-are-necessary-but-congress-destroys-them-says-pm-narendra-modi
https://thelogicalindian.com/india-united-nations-delhi-protest-131180/
https://www.tribuneindia.com/news/india/cong-slams-ec-says-it-stands-exposed-for-bias-inefficiency
https://www.tribuneindia.com/news/india/oppn-mulls-impeachment-motion-against-cec
https://www.nktv.in/india-bloc-prepares-to-revive-protest-demanding-gyanesh-kumars-resignation-leaders-to-meet-today/
https://www.thequint.com/amp/story/news/politics/gyanesh-kumar-resignation-protest-cjp-delh-jantar-mantar-aisa-neha-bora
https://theconversation.com/indian-gen-z-cockroach-protesters-aim-to-upend-assumptions-about-democracy-and-populism-in-modis-india-294730
https://www.outlookindia.com/national/in-photos-gyanesh-kumar-resignation-protests-spread-from-delhi-to-hyderabad-srinagar
https://www.aljazeera.com/news/2026/10/6/india-opposition-leaders-briefly-arrested-in-protest-against-election-chief
https://www.aljazeera.com/news/2026/10/10/indias-cockroach-leaders-detained-new-delhi-in-lockdown
https://www.aljazeera.com/news/2026/10/19/indian-court-extends-detention-of-cockroach-leaders
https://www.npr.org/2026/10/12/nx-s1-5998831/india-opposition-protests
https://en.wikipedia.org/wiki/HTTP/2
https://en.wikipedia.org/wiki/HTTP/3
https://en.wikipedia.org/wiki/QUIC
https://en.wikipedia.org/wiki/BERT_(language_model)
https://en.wikipedia.org/wiki/Raft_(algorithm)
https://en.wikipedia.org/wiki/Etcd
https://en.wikipedia.org/wiki/Kubernetes
https://en.wikipedia.org/wiki/PostgreSQL
https://en.wikipedia.org/wiki/MongoDB
```
