create table media_download_task (
    id uuid primary key,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    device_id varchar(128),
    title varchar(300) not null,
    media_type varchar(16) not null,
    release_year integer,
    season_number integer,
    episode_number integer,
    episode_title varchar(300),
    source_provider varchar(64) not null,
    source_share_id varchar(256),
    source_url text not null,
    original_filename varchar(512) not null,
    staging_path text not null,
    expected_size_bytes bigint,
    downloaded_bytes bigint not null default 0,
    status varchar(32) not null,
    failure_code varchar(64),
    failure_message varchar(1000),
    verify_started_at timestamp with time zone,
    completed_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_media_download_scope_created
    on media_download_task (tenant_id, user_id, created_at);

create index idx_media_download_status
    on media_download_task (status, updated_at);

create table media_asset (
    id uuid primary key,
    download_id uuid not null unique,
    tenant_id varchar(128) not null,
    user_id varchar(128) not null,
    title varchar(300) not null,
    media_type varchar(16) not null,
    release_year integer,
    season_number integer,
    episode_number integer,
    episode_title varchar(300),
    file_path text not null unique,
    file_size_bytes bigint not null,
    sha256 varchar(64) not null,
    container varchar(64),
    video_codec varchar(64),
    audio_codec varchar(64),
    duration_seconds bigint,
    width integer,
    height integer,
    jellyfin_item_id varchar(128),
    jellyfin_image_tag varchar(256),
    jellyfin_sync_status varchar(32) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint fk_media_asset_download foreign key (download_id) references media_download_task(id)
);

create index idx_media_asset_scope_created
    on media_asset (tenant_id, user_id, created_at);

create index idx_media_asset_jellyfin_sync
    on media_asset (jellyfin_sync_status, updated_at);
