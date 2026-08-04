create table insight_report (
    id uuid primary key,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    domain varchar(32) not null,
    period_type varchar(32) not null,
    period_start timestamp with time zone not null,
    period_end timestamp with time zone not null,
    data_cutoff timestamp with time zone not null,
    currency varchar(3),
    evidence_hash varchar(128) not null,
    evidence_snapshot text not null,
    title varchar(300) not null,
    summary text not null,
    findings text not null,
    cautions text not null,
    provider varchar(64) not null,
    model_name varchar(256),
    prompt_version varchar(64) not null,
    generation_mode varchar(32) not null,
    supersedes_insight_id uuid,
    created_at timestamp with time zone not null,
    constraint fk_insight_supersedes foreign key (supersedes_insight_id) references insight_report(id)
);

create index idx_insight_scope_domain_created
    on insight_report (tenant_id, user_id, domain, created_at);

create index idx_insight_scope_period
    on insight_report (tenant_id, user_id, domain, period_type, period_start, period_end);

create table insight_evidence (
    insight_id uuid not null,
    evidence_type varchar(32) not null,
    record_id uuid not null,
    occurred_at timestamp with time zone not null,
    snapshot text not null,
    primary key (insight_id, evidence_type, record_id),
    constraint fk_insight_evidence_report foreign key (insight_id) references insight_report(id) on delete cascade
);

create index idx_insight_evidence_record on insight_evidence (evidence_type, record_id);

create table insight_feedback (
    id uuid primary key,
    insight_id uuid not null,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    rating varchar(32) not null,
    comment varchar(1000),
    created_at timestamp with time zone not null,
    constraint fk_insight_feedback_report foreign key (insight_id) references insight_report(id) on delete cascade
);

create index idx_insight_feedback_report on insight_feedback (insight_id, created_at);

create table insight_follow_up (
    id uuid primary key,
    insight_id uuid not null,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    question varchar(1000) not null,
    answer text not null,
    provider varchar(64) not null,
    model_name varchar(256),
    prompt_version varchar(64) not null,
    created_at timestamp with time zone not null,
    constraint fk_insight_follow_up_report foreign key (insight_id) references insight_report(id) on delete cascade
);

create index idx_insight_follow_up_report on insight_follow_up (insight_id, created_at);
