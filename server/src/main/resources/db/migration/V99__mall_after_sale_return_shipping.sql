ALTER TABLE mall_after_sale
    ADD COLUMN return_carrier VARCHAR(64) NULL COMMENT '退货快递公司',
    ADD COLUMN return_tracking_no VARCHAR(100) NULL COMMENT '退货快递单号',
    ADD COLUMN return_shipped_time DATETIME NULL COMMENT '买家填写退货物流时间',
    ADD COLUMN return_received_time DATETIME NULL COMMENT '商家确认收到退货时间',
    ADD COLUMN return_receiver VARCHAR(64) NULL COMMENT '退货收件确认人';
