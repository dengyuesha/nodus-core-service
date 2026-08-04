create table memo (
    id uuid primary key,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    household_id varchar(128),
    device_id varchar(128),
    text varchar(2000) not null,
    raw_text varchar(4000),
    status varchar(32) not null,
    version integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    deleted_at timestamp with time zone
);

create index idx_memo_scope_status_time
    on memo (tenant_id, user_id, status, created_at);

create table reminder (
    id uuid primary key,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    household_id varchar(128),
    device_id varchar(128),
    session_id varchar(128),
    memo_id uuid,
    text varchar(2000) not null,
    kind varchar(64) not null,
    timezone varchar(64) not null,
    due_at timestamp with time zone not null,
    status varchar(32) not null,
    delivery_attempt integer not null,
    next_retry_at timestamp with time zone,
    version integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    cancelled_at timestamp with time zone,
    constraint fk_reminder_memo foreign key (memo_id) references memo (id)
);

create index idx_reminder_due
    on reminder (status, due_at);

create index idx_reminder_scope_time
    on reminder (tenant_id, user_id, created_at);

create table reminder_delivery (
    id uuid primary key,
    event_id varchar(128) not null,
    reminder_id uuid not null,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    device_id varchar(128),
    session_id varchar(128),
    attempt integer not null,
    status varchar(32) not null,
    lease_until timestamp with time zone,
    next_retry_at timestamp with time zone not null,
    payload text not null,
    last_error varchar(1000),
    created_at timestamp with time zone not null,
    delivered_at timestamp with time zone,
    acknowledged_at timestamp with time zone,
    ack_source varchar(128),
    constraint uk_reminder_delivery_event unique (event_id),
    constraint fk_delivery_reminder foreign key (reminder_id) references reminder (id)
);

create index idx_reminder_delivery_claim
    on reminder_delivery (status, next_retry_at, lease_until, created_at);
