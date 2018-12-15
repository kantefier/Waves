
-- table for blocks
CREATE TABLE public.blocks
(
  schema_version                     smallint                    NOT NULL,
  time_stamp                         timestamp without time zone NOT NULL,
  reference                          character varying           NOT NULL,
  nxt_consensus_base_target          bigint                      NOT NULL,
  nxt_consensus_generation_signature character varying           NOT NULL,
  generator                          character varying           NOT NULL,
  signature                          character varying           NOT NULL,
  fee                                bigint                      NOT NULL,
  blocksize                          integer,
  height                             integer                     NOT NULL,
  features                           smallint[]
);

-- common table for all transactions
CREATE TABLE public.transactions
(
  height            integer                     NOT NULL,
  tx_type           smallint                    NOT NULL,
  id                character varying           NOT NULL,
  time_stamp        timestamp without time zone NOT NULL,
  signature         character varying,
  proofs            character varying[],
  tx_version        smallint,
  sender            character varying,
  sender_public_key character varying
);

-- type = 1
CREATE TABLE public.genesis_transactions
(
  fee       bigint            NOT NULL,
  recipient character varying NOT NULL,
  amount    bigint            NOT NULL
)
  INHERITS (public.transactions);

-- type = 2
CREATE TABLE public.payment_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL,
  recipient         character varying NOT NULL,
  amount            bigint            NOT NULL
)
  INHERITS (public.transactions);

-- type = 3
CREATE TABLE public.issue_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL,
  asset_id          character varying NOT NULL,
  asset_name        character varying NOT NULL,
  description       character varying NOT NULL,
  quantity          bigint            NOT NULL,
  decimals          smallint          NOT NULL,
  reissuable        boolean           NOT NULL
)
  INHERITS (public.transactions);

-- type = 4
CREATE TABLE public.transfer_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL,
  asset_id          character varying NOT NULL,
  amount            bigint            NOT NULL,
  recipient         character varying NOT NULL,
  fee_asset         character varying NOT NULL,
  attachment        character varying NOT NULL
)
  INHERITS (public.transactions);

-- type = 5
CREATE TABLE public.reissue_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL,
  asset_id          character varying NOT NULL,
  quantity          bigint            NOT NULL,
  reissuable        boolean           NOT NULL
)
  INHERITS (public.transactions);


-- type = 6
CREATE TABLE public.burn_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL,
  asset_id          character varying NOT NULL,
  amount            bigint            NOT NULL
)
  INHERITS (public.transactions);

-- type = 7
CREATE TABLE public.exchange_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL,
  order1            jsonb             NOT NULL,
  order2            jsonb             NOT NULL,
  amount_asset      character varying NOT NULL,
  price_asset       character varying NOT NULL,
  amount            bigint            NOT NULL,
  price             bigint            NOT NULL,
  buy_matcher_fee   bigint            NOT NULL,
  sell_matcher_fee  bigint            NOT NULL
)
  INHERITS (public.transactions);

-- type = 8
CREATE TABLE public.lease_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL,
  recipient         character varying NOT NULL,
  amount            bigint            NOT NULL
)
  INHERITS (public.transactions);

-- type = 9
CREATE TABLE public.lease_cancel_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL,
  lease_id          character varying NOT NULL
)
  INHERITS (public.transactions);

-- type = 10
CREATE TABLE public.create_alias_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL,
  alias             character varying NOT NULL
)
  INHERITS (public.transactions);

-- type = 11
CREATE TABLE public.mass_transfer_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL,
  asset_id          character varying NOT NULL,
  attachment        character varying NOT NULL
)
  INHERITS (public.transactions);

CREATE TABLE public.mass_transfer_transactions_transfers
(
  tx_id          character varying NOT NULL,
  recipient      character varying NOT NULL,
  amount         bigint            NOT NULL,
  position_in_tx smallint          NOT NULL
);

-- type = 12
CREATE TABLE public.data_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL
)
  INHERITS (public.transactions);

CREATE TABLE public.data_transactions_data
(
  tx_id              text     NOT NULL,
  data_key           text     NOT NULL,
  data_type          text     NOT NULL,
  data_value_integer bigint,
  data_value_boolean boolean,
  data_value_binary  text,
  data_value_string  text,
  position_in_tx     smallint NOT NULL
);

-- type = 13
CREATE TABLE public.set_script_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL,
  script            character varying
)
  INHERITS (public.transactions);

-- type = 14
CREATE TABLE public.sponsor_fee_transactions
(
  sender                  character varying NOT NULL,
  sender_public_key       character varying NOT NULL,
  fee                     bigint            NOT NULL,
  asset_id                character varying NOT NULL,
  min_sponsored_asset_fee bigint
)
  INHERITS (public.transactions);

-- type = 15
CREATE TABLE public.set_asset_script_transactions
(
  sender            character varying NOT NULL,
  sender_public_key character varying NOT NULL,
  fee               bigint            NOT NULL,
  asset_id          character varying NOT NULL,
  script            character varying
)
  INHERITS (public.transactions);







