-- Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
-- and the EPL 1.0 (http://h2database.com/html/license.html).
-- Initial Developer: H2 Group
--

-- with filter condition

create table test(v int);
> ok

insert into test values (1), (2), (3), (4), (5), (6), (7), (8), (9), (10), (11), (12);
> update count: 12

select sum(v), sum(v) filter (where v >= 4) from test where v <= 10;
> SUM(V) SUM(V) FILTER (WHERE (V >= 4))
> ------ ------------------------------
> 55     49
> rows: 1

create index test_idx on test(v);
> ok

select sum(v), sum(v) filter (where v >= 4) from test where v <= 10;
> SUM(V) SUM(V) FILTER (WHERE (V >= 4))
> ------ ------------------------------
> 55     49
> rows: 1

drop table test;
> ok

create table test(v interval day to second);
> ok

insert into test values ('0 1'), ('0 2'), ('0 2'), ('0 2'), ('-0 1'), ('-0 1');
> update count: 6

select sum(v) from test;
>> INTERVAL '0 05:00:00' DAY TO SECOND

drop table test;
> ok

SELECT X, COUNT(*), SUM(COUNT(*)) OVER() FROM VALUES (1), (1), (1), (1), (2), (2), (3) T(X) GROUP BY X;
> X COUNT(*) SUM(COUNT(*)) OVER ()
> - -------- ---------------------
> 1 4        7
> 2 2        7
> 3 1        7
> rows: 3

CREATE TABLE TEST(ID INT);
> ok

SELECT SUM(ID) FROM TEST;
>> null

SELECT SUM(ID) OVER () FROM TEST;
> SUM(ID) OVER ()
> ---------------
> rows: 0

DROP TABLE TEST;
> ok
