create table device_registration (
    id uuid primary key,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    household_id varchar(128),
    device_id varchar(128) not null,
    display_name varchar(256),
    status varchar(32) not null,
    registered_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_device_registration_tenant_device unique (tenant_id, device_id)
);

create index idx_device_registration_user
    on device_registration (tenant_id, user_id);

create table idempotency_record (
    id uuid primary key,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    operation varchar(128) not null,
    idempotency_key varchar(256) not null,
    request_hash varchar(128) not null,
    status varchar(32) not null,
    response_code integer,
    response_body text,
    created_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    expires_at timestamp with time zone not null,
    constraint uk_idempotency_scope unique (tenant_id, user_id, operation, idempotency_key)
);

create index idx_idempotency_expiry
    on idempotency_record (expires_at);

create table outbox_event (
    id uuid primary key,
    event_id varchar(128) not null,
    request_id varchar(128) not null,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    device_id varchar(128),
    session_id varchar(128),
    event_type varchar(128) not null,
    aggregate_type varchar(128) not null,
    aggregate_id varchar(128) not null,
    payload text not null,
    status varchar(32) not null,
    attempt_count integer not null,
    next_attempt_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    published_at timestamp with time zone,
    constraint uk_outbox_event_id unique (event_id)
);

create index idx_outbox_pending
    on outbox_event (status, next_attempt_at, created_at);

create table audit_record (
    id uuid primary key,
    request_id varchar(128) not null,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    device_id varchar(128),
    session_id varchar(128),
    source_client varchar(128),
    action varchar(128) not null,
    resource_type varchar(128) not null,
    resource_id varchar(128),
    details text not null,
    occurred_at timestamp with time zone not null
);

create index idx_audit_scope_time
    on audit_record (tenant_id, user_id, occurred_at);

