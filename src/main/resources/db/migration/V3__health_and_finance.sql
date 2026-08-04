create table health_record (
    id uuid primary key,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    source_system varchar(128) not null,
    source_record_id varchar(256) not null,
    metric_type varchar(128) not null,
    metric_value decimal(19,6) not null,
    unit varchar(64) not null,
    measured_at timestamp with time zone not null,
    metadata text not null,
    content_hash varchar(128) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_health_source_record unique (tenant_id, user_id, source_system, source_record_id)
);

create index idx_health_scope_metric_time
    on health_record (tenant_id, user_id, metric_type, measured_at);

create table financial_record (
    id uuid primary key,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    source_system varchar(128) not null,
    source_record_id varchar(256) not null,
    record_type varchar(64) not null,
    amount decimal(19,4) not null,
    currency varchar(3) not null,
    category varchar(128),
    account_name varchar(256),
    description varchar(1000),
    occurred_at timestamp with time zone not null,
    metadata text not null,
    content_hash varchar(128) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uk_finance_source_record unique (tenant_id, user_id, source_system, source_record_id)
);

create index idx_finance_scope_type_time
    on financial_record (tenant_id, user_id, record_type, occurred_at);

create index idx_finance_scope_currency_time
    on financial_record (tenant_id, user_id, currency, occurred_at);
