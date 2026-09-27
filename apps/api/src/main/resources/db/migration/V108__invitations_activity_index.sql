-- The index the invitations activity read wants, and V105 said it would want it.
--
-- V105's own words: "activity read (still recorded as a gap) is the one that would want it. An index for a read that does
-- not exist is a write cost nobody pays for, and this file is where that would be noticed - the members migration created
-- its index because the members read existed." The read exists now (ADR 0044), so the cost is paid here and the reason is
-- the query: `GET /groups/{groupId}/activity` asks for one group's invitations newest first, and without this the read scans
-- every invitation in the deployment.
--
-- Ordered descending because the feed is ordered that way and the read is always clamped to a page: the index's order is the
-- answer's order, so the database can stop after `limit` rows rather than sorting the group's history on every request.

CREATE INDEX invitations_by_group ON invitations (group_id, created_at DESC);
