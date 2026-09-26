package com.wuling.finance;

import com.wuling.finance.entity.FundFlow;
import com.wuling.finance.entity.ReconcileIssue;
import com.wuling.finance.entity.SettlementRecord;
import com.wuling.finance.entity.SplitRule;
import com.wuling.finance.entity.SplitSnapshot;
import com.wuling.finance.entity.SubjectAccount;
import com.wuling.finance.mapper.FundFlowMapper;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.finance.mapper.SplitRuleMapper;
import com.wuling.finance.mapper.SplitSnapshotMapper;
import com.wuling.finance.mapper.SubjectAccountMapper;
import com.wuling.finance.service.LedgerService;
import com.wuling.finance.service.ReconcileService;
import com.wuling.finance.service.SplitCalculator;
import com.wuling.subject.port.SubjectQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一记账、核销即结算与退款冲正的行为测试。
 *
 * <p>测试只 mock 数据库 Mapper、主体端口和事务管理器，账务计算与 LedgerService
 * 业务逻辑使用真实对象，避免把断言变成对 mock 自身行为的验证。
 */
class LedgerServiceTest {

    private SplitRuleMapper splitRuleMapper;
    private SplitSnapshotMapper splitSnapshotMapper;
    private SubjectAccountMapper subjectAccountMapper;
    private FundFlowMapper fundFlowMapper;
    private SettlementRecordMapper settlementRecordMapper;
    private ReconcileService reconcileService;
    private SubjectQueryPort subjectQueryPort;
    private PlatformTransactionManager transactionManager;
    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        splitRuleMapper = mock(SplitRuleMapper.class);
        splitSnapshotMapper = mock(SplitSnapshotMapper.class);
        subjectAccountMapper = mock(SubjectAccountMapper.class);
        fundFlowMapper = mock(FundFlowMapper.class);
        settlementRecordMapper = mock(SettlementRecordMapper.class);
        reconcileService = mock(ReconcileService.class);
        subjectQueryPort = mock(SubjectQueryPort.class);
        transactionManager = mock(PlatformTransactionManager.class);

        when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        when(subjectAccountMapper.updateById(any(SubjectAccount.class))).thenReturn(1);
        when(settlementRecordMapper.updateById(any(SettlementRecord.class))).thenReturn(1);
        when(fundFlowMapper.insert(any(FundFlow.class))).thenReturn(1);

        ledgerService = new LedgerService(
                splitRuleMapper,
                splitSnapshotMapper,
                subjectAccountMapper,
                fundFlowMapper,
                settlementRecordMapper,
                reconcileService,
                subjectQueryPort,
                new SplitCalculator(),
                transactionManager);
    }

    @Test
    void postIncomeUpdatesAvailableBalanceAndWritesCompleteSnapshot() {
        SubjectAccount account = account(10L, 100L, "STORE", 500L, 0L);
        stubSingleAccount(account);

        FundFlow flow = ledgerService.post(new LedgerService.Posting(
                100L,
                "STORE",
                "INCOME",
                250L,
                LedgerService.BUCKET_AVAILABLE,
                88L,
                "ORDER-88",
                99L,
                "ORDER",
                "ORDER-88",
                LedgerService.SETTLE_SETTLED,
                "订单核销分账入账"));

        assertEquals(750L, account.getAvailableBalance());
        assertEquals(0L, account.getFrozenBalance());
        assertEquals(250L, flow.getAmount());
        assertEquals(250L, flow.getChangeAmount());
        assertEquals("INCOME", flow.getType());
        assertEquals("in", flow.getDirection());
        assertEquals(LedgerService.BUCKET_AVAILABLE, flow.getBalanceBucket());
        assertEquals(500L, flow.getBalanceBefore());
        assertEquals(750L, flow.getBalanceAfter());
        assertEquals(10L, flow.getAccountId());
        assertEquals(100L, flow.getSubjectId());
        assertEquals(88L, flow.getOrderId());
        assertEquals("ORDER-88", flow.getOrderNo());
        assertEquals(99L, flow.getSettlementRecordId());
        assertEquals("ORDER", flow.getBizType());
        assertEquals("ORDER-88", flow.getBizNo());
        assertEquals(LedgerService.SETTLE_SETTLED, flow.getSettlementStatus());
        assertEquals("STORE", flow.getRoleType());
        assertNotNull(flow.getFlowNo());

        ArgumentCaptor<FundFlow> captor = ArgumentCaptor.forClass(FundFlow.class);
        verify(fundFlowMapper).insert(captor.capture());
        assertEquals(flow, captor.getValue());
    }

    @Test
    void freezeMovesAvailableBalanceIntoFrozenBucketAndWritesFrozenSnapshot() {
        SubjectAccount account = account(11L, 101L, "STORE", 1_000L, 200L);
        stubSingleAccount(account);

        FundFlow flow = ledgerService.post(posting(account.getSubjectId(), "FREEZE", 300L, LedgerService.BUCKET_FROZEN));

        assertEquals(700L, account.getAvailableBalance());
        assertEquals(500L, account.getFrozenBalance());
        assertFrozenFlow(flow, 200L, 300L, 500L, "in");
    }

    @Test
    void unfreezeMovesFrozenBalanceBackIntoAvailableBucketAndWritesFrozenSnapshot() {
        SubjectAccount account = account(12L, 102L, "STORE", 1_000L, 500L);
        stubSingleAccount(account);

        FundFlow flow = ledgerService.post(posting(account.getSubjectId(), "UNFREEZE", -200L, LedgerService.BUCKET_FROZEN));

        assertEquals(1_200L, account.getAvailableBalance());
        assertEquals(300L, account.getFrozenBalance());
        assertFrozenFlow(flow, 500L, -200L, 300L, "out");
    }

    @Test
    void withdrawOnlyReducesFrozenBucketAndWritesFrozenSnapshot() {
        SubjectAccount account = account(13L, 103L, "STORE", 1_000L, 500L);
        stubSingleAccount(account);

        FundFlow flow = ledgerService.post(posting(account.getSubjectId(), "WITHDRAW", -400L, LedgerService.BUCKET_FROZEN));

        assertEquals(1_000L, account.getAvailableBalance());
        assertEquals(100L, account.getFrozenBalance());
        assertEquals(400L, account.getTotalWithdrawn());
        assertFrozenFlow(flow, 500L, -400L, 100L, "out");
    }

    @Test
    void freezeWithInsufficientAvailableBalanceThrowsAndDoesNotWriteFlow() {
        SubjectAccount account = account(14L, 104L, "STORE", 100L, 0L);
        stubSingleAccount(account);

        assertThrows(LedgerService.InsufficientBalanceException.class,
                () -> ledgerService.post(posting(account.getSubjectId(), "FREEZE", 200L, LedgerService.BUCKET_FROZEN)));

        assertEquals(100L, account.getAvailableBalance());
        assertEquals(0L, account.getFrozenBalance());
        verify(subjectAccountMapper, never()).updateById(any(SubjectAccount.class));
        verify(fundFlowMapper, never()).insert(any(FundFlow.class));
    }

    @Test
    void withdrawWithInsufficientFrozenBalanceThrowsAndDoesNotWriteFlow() {
        SubjectAccount account = account(15L, 105L, "STORE", 1_000L, 100L);
        stubSingleAccount(account);

        assertThrows(LedgerService.InsufficientBalanceException.class,
                () -> ledgerService.post(posting(account.getSubjectId(), "WITHDRAW", -200L, LedgerService.BUCKET_FROZEN)));

        assertEquals(1_000L, account.getAvailableBalance());
        assertEquals(100L, account.getFrozenBalance());
        verify(subjectAccountMapper, never()).updateById(any(SubjectAccount.class));
        verify(fundFlowMapper, never()).insert(any(FundFlow.class));
    }

    @Test
    void executeSplitSettlesImmediatelyAndIsIdempotentForSameOrder() {
        long orderId = 9_001L;
        String orderNo = "ORDER-9001";
        SplitRule rule = new SplitRule();
        rule.setStoreRatio(1_000);

        SubjectAccount platformAccount = account(21L, 1L, "PLATFORM", 0L, 0L);
        SubjectAccount storeAccount = account(22L, 2L, "STORE", 0L, 0L);
        when(splitRuleMapper.selectOne(any())).thenReturn(rule);
        when(subjectQueryPort.findInvestorOfStore(2L)).thenReturn(null);
        when(subjectQueryPort.findFirstByType("PLATFORM")).thenReturn(1L);
        when(subjectQueryPort.findById(anyLong())).thenAnswer(invocation -> {
            Long subjectId = invocation.getArgument(0);
            return subject(subjectId, subjectId == 1L ? "PLATFORM" : "STORE");
        });
        when(subjectAccountMapper.selectOne(any())).thenReturn(platformAccount, storeAccount);
        when(subjectAccountMapper.selectBySubjectIdForUpdate(anyLong()))
                .thenReturn(platformAccount, storeAccount);

        AtomicReference<SplitSnapshot> storedSnapshot = new AtomicReference<>();
        when(splitSnapshotMapper.selectOne(any())).thenAnswer(invocation -> storedSnapshot.get());
        doAnswer(invocation -> {
            SplitSnapshot snapshot = invocation.getArgument(0);
            snapshot.setId(501L);
            storedSnapshot.set(snapshot);
            return 1;
        }).when(splitSnapshotMapper).insert(any(SplitSnapshot.class));

        AtomicLong settlementId = new AtomicLong(700L);
        doAnswer(invocation -> {
            SettlementRecord record = invocation.getArgument(0);
            record.setId(settlementId.incrementAndGet());
            return 1;
        }).when(settlementRecordMapper).insert(any(SettlementRecord.class));

        SplitSnapshot first = ledgerService.executeSplit(
                orderId, orderNo, 10_000L, 2, 2L, null, null, 0L,
                List.of(new SplitCalculator.LineItem(3L, 10_000L, 0L, 0L, 2)));
        SplitSnapshot second = ledgerService.executeSplit(
                orderId, orderNo, 10_000L, 2, 2L, null, null, 0L,
                List.of(new SplitCalculator.LineItem(3L, 10_000L, 0L, 0L, 2)));

        assertEquals(501L, first.getId());
        assertEquals(first, second);
        assertEquals(8_000L, platformAccount.getAvailableBalance());
        assertEquals(2_000L, storeAccount.getAvailableBalance());
        assertEquals(0L, platformAccount.getFrozenBalance());
        assertEquals(0L, storeAccount.getFrozenBalance());

        ArgumentCaptor<SettlementRecord> recordCaptor = ArgumentCaptor.forClass(SettlementRecord.class);
        verify(settlementRecordMapper, times(2)).insert(recordCaptor.capture());
        List<SettlementRecord> records = recordCaptor.getAllValues();
        assertEquals(2, records.size());
        for (SettlementRecord record : records) {
            assertEquals(LedgerService.SETTLE_SETTLED, record.getStatus());
            assertEquals(orderId, record.getOrderId());
            assertEquals(501L, record.getSnapshotId());
            assertNotNull(record.getRecordNo());
            assertTrue(record.getAmount() > 0L);
            assertEquals(LocalDate.now(), record.getSettleDate());
        }
        assertEquals(8_000L, records.get(0).getAmount());
        assertEquals(2_000L, records.get(1).getAmount());

        ArgumentCaptor<FundFlow> flowCaptor = ArgumentCaptor.forClass(FundFlow.class);
        verify(fundFlowMapper, times(2)).insert(flowCaptor.capture());
        List<FundFlow> flows = flowCaptor.getAllValues();
        assertEquals(2, flows.size());
        for (FundFlow flow : flows) {
            assertEquals("INCOME", flow.getType());
            assertEquals(LedgerService.BUCKET_AVAILABLE, flow.getBalanceBucket());
            assertEquals(LedgerService.SETTLE_SETTLED, flow.getSettlementStatus());
            assertEquals(orderId, flow.getOrderId());
            assertEquals(orderNo, flow.getOrderNo());
            assertEquals("ORDER", flow.getBizType());
            assertEquals(orderNo, flow.getBizNo());
            assertNotNull(flow.getAccountId());
            assertNotNull(flow.getSettlementRecordId());
            assertEquals(0L, flow.getBalanceBefore());
            assertEquals(flow.getAmount(), flow.getChangeAmount());
            assertEquals(flow.getAmount(), flow.getBalanceAfter());
        }
        verify(splitSnapshotMapper, times(1)).insert(any(SplitSnapshot.class));
    }

    @Test
    void reverseSettledOrderDeductsAvailableBalanceAndWritesNegativeRefundFlow() {
        String orderNo = "ORDER-REFUND-1";
        String refundNo = "REFUND-1";
        long orderId = 9_100L;
        SettlementRecord record = settlementRecord(701L, 201L, orderId, 500L, LedgerService.SETTLE_SETTLED);
        SubjectAccount account = account(31L, 201L, "STORE", 800L, 0L);

        when(splitSnapshotMapper.selectOrderIdByNo(orderNo)).thenReturn(orderId);
        when(settlementRecordMapper.selectList(any())).thenReturn(List.of(record));
        stubSingleAccount(account);

        ledgerService.reverseForOrder(orderNo, refundNo);

        assertEquals(300L, account.getAvailableBalance());
        assertEquals(LedgerService.SETTLE_CANCELED, record.getStatus());
        ArgumentCaptor<FundFlow> captor = ArgumentCaptor.forClass(FundFlow.class);
        verify(fundFlowMapper).insert(captor.capture());
        FundFlow flow = captor.getValue();
        assertEquals("REFUND", flow.getType());
        assertEquals("out", flow.getDirection());
        assertEquals(LedgerService.BUCKET_AVAILABLE, flow.getBalanceBucket());
        assertEquals(800L, flow.getBalanceBefore());
        assertEquals(-500L, flow.getChangeAmount());
        assertEquals(300L, flow.getBalanceAfter());
        assertEquals(500L, flow.getAmount());
        assertEquals(orderId, flow.getOrderId());
        assertEquals(orderNo, flow.getOrderNo());
        assertEquals(record.getId(), flow.getSettlementRecordId());
        assertEquals("REFUND", flow.getBizType());
        assertEquals(refundNo, flow.getBizNo());
        assertEquals(LedgerService.SETTLE_SETTLED, flow.getSettlementStatus());
        verify(reconcileService, never()).recordOnce(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void reverseHistoricalPendingOrderOnlyCancelsSettlementWithoutFabricatingFlow() {
        String orderNo = "ORDER-PENDING-1";
        long orderId = 9_200L;
        SettlementRecord record = settlementRecord(702L, 202L, orderId, 600L, LedgerService.SETTLE_PENDING);

        when(splitSnapshotMapper.selectOrderIdByNo(orderNo)).thenReturn(orderId);
        when(settlementRecordMapper.selectList(any())).thenReturn(List.of(record));

        ledgerService.reverseForOrder(orderNo, "REFUND-PENDING-1");

        assertEquals(LedgerService.SETTLE_CANCELED, record.getStatus());
        verify(fundFlowMapper, never()).insert(any(FundFlow.class));
        verify(subjectAccountMapper, never()).updateById(any(SubjectAccount.class));
        verify(reconcileService, never()).recordOnce(any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void reverseSettledOrderWithInsufficientBalanceRollsBackAndRecordsReconcileIssue() {
        String orderNo = "ORDER-REFUND-SHORT";
        String refundNo = "REFUND-SHORT";
        long orderId = 9_300L;
        SettlementRecord record = settlementRecord(703L, 203L, orderId, 500L, LedgerService.SETTLE_SETTLED);
        SubjectAccount account = account(32L, 203L, "STORE", 300L, 0L);

        when(splitSnapshotMapper.selectOrderIdByNo(orderNo)).thenReturn(orderId);
        when(settlementRecordMapper.selectList(any())).thenReturn(List.of(record));
        stubSingleAccount(account);

        ledgerService.reverseForOrder(orderNo, refundNo);

        assertEquals(300L, account.getAvailableBalance());
        assertEquals(LedgerService.SETTLE_SETTLED, record.getStatus());
        verify(subjectAccountMapper, never()).updateById(any(SubjectAccount.class));
        verify(fundFlowMapper, never()).insert(any(FundFlow.class));
        verify(reconcileService).recordOnce(
                eq(ReconcileIssue.TYPE_REFUND_BALANCE_SHORTAGE),
                eq(orderNo),
                eq(refundNo),
                any(),
                any(),
                eq(200L));
    }

    private LedgerService.Posting posting(long subjectId, String type, long changeAmount, String bucket) {
        return new LedgerService.Posting(
                subjectId,
                "STORE",
                type,
                changeAmount,
                bucket,
                null,
                null,
                null,
                "WITHDRAWAL",
                "WD-1",
                null,
                "test");
    }

    private SubjectAccount account(long id, long subjectId, String roleType, long available, long frozen) {
        SubjectAccount account = new SubjectAccount();
        account.setId(id);
        account.setSubjectId(subjectId);
        account.setRoleType(roleType);
        account.setAvailableBalance(available);
        account.setFrozenBalance(frozen);
        account.setTotalIncome(0L);
        account.setTotalWithdrawn(0L);
        return account;
    }

    private SettlementRecord settlementRecord(long id, long subjectId, long orderId, long amount, String status) {
        SettlementRecord record = new SettlementRecord();
        record.setId(id);
        record.setRecordNo("SR-" + id);
        record.setSubjectId(subjectId);
        record.setOrderId(orderId);
        record.setAmount(amount);
        record.setStatus(status);
        return record;
    }

    private SubjectQueryPort.SubjectView subject(Long id, String type) {
        SubjectQueryPort.SubjectView view = new SubjectQueryPort.SubjectView();
        view.setId(id);
        view.setSubjectType(type);
        return view;
    }

    private void stubSingleAccount(SubjectAccount account) {
        when(subjectAccountMapper.selectOne(any())).thenReturn(account);
        when(subjectAccountMapper.selectBySubjectIdForUpdate(eq(account.getSubjectId()))).thenReturn(account);
    }

    private void assertFrozenFlow(FundFlow flow, long before, long change, long after, String direction) {
        assertEquals("WITHDRAWAL", flow.getBizType());
        assertEquals("WD-1", flow.getBizNo());
        assertEquals(LedgerService.BUCKET_FROZEN, flow.getBalanceBucket());
        assertEquals(before, flow.getBalanceBefore());
        assertEquals(change, flow.getChangeAmount());
        assertEquals(after, flow.getBalanceAfter());
        assertEquals(direction, flow.getDirection());
        assertEquals(Math.abs(change), flow.getAmount());
    }
}
