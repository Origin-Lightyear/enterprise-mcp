package com.kevin.mcp.tools.internal;

import com.kevin.mcp.annotation.PrivateMcpTool;
import com.kevin.mcp.annotation.PrivateMcpToolParam;
import com.kevin.mcp.entity.FinanceJournal;
import com.kevin.mcp.entity.FinanceSettlement;
import com.kevin.mcp.entity.FulfillmentTask;
import com.kevin.mcp.entity.InventoryTransfer;
import com.kevin.mcp.entity.ItemBrand;
import com.kevin.mcp.entity.ItemCategory;
import com.kevin.mcp.entity.ItemSku;
import com.kevin.mcp.entity.MemberCoupon;
import com.kevin.mcp.entity.MemberInfo;
import com.kevin.mcp.entity.MemberLevel;
import com.kevin.mcp.entity.MemberPointsLog;
import com.kevin.mcp.entity.OnlineOrder;
import com.kevin.mcp.entity.OnlineOrderDetail;
import com.kevin.mcp.entity.OrgCompany;
import com.kevin.mcp.entity.OrgRegion;
import com.kevin.mcp.entity.OrgStore;
import com.kevin.mcp.entity.PosOrder;
import com.kevin.mcp.entity.PosOrderDetail;
import com.kevin.mcp.entity.PosPayment;
import com.kevin.mcp.entity.PromotionRule;
import com.kevin.mcp.entity.PromotionScopeItem;
import com.kevin.mcp.entity.PromotionScopeStore;
import com.kevin.mcp.entity.PurchaseOrder;
import com.kevin.mcp.entity.PurchaseOrderDetail;
import com.kevin.mcp.entity.StoreShift;
import com.kevin.mcp.entity.StoreStock;
import com.kevin.mcp.entity.Supplier;
import com.kevin.mcp.entity.SysUser;
import com.kevin.mcp.entity.WarehouseReceipt;
import com.kevin.mcp.entity.WarehouseStock;
import com.kevin.mcp.mapper.FinanceJournalMapper;
import com.kevin.mcp.mapper.FinanceSettlementMapper;
import com.kevin.mcp.mapper.FulfillmentTaskMapper;
import com.kevin.mcp.mapper.InventoryTransferMapper;
import com.kevin.mcp.mapper.ItemBrandMapper;
import com.kevin.mcp.mapper.ItemCategoryMapper;
import com.kevin.mcp.mapper.ItemSkuMapper;
import com.kevin.mcp.mapper.MemberCouponMapper;
import com.kevin.mcp.mapper.MemberInfoMapper;
import com.kevin.mcp.mapper.MemberLevelMapper;
import com.kevin.mcp.mapper.MemberPointsLogMapper;
import com.kevin.mcp.mapper.OnlineOrderDetailMapper;
import com.kevin.mcp.mapper.OnlineOrderMapper;
import com.kevin.mcp.mapper.OrgCompanyMapper;
import com.kevin.mcp.mapper.OrgRegionMapper;
import com.kevin.mcp.mapper.OrgStoreMapper;
import com.kevin.mcp.mapper.PosOrderDetailMapper;
import com.kevin.mcp.mapper.PosOrderMapper;
import com.kevin.mcp.mapper.PosPaymentMapper;
import com.kevin.mcp.mapper.PromotionRuleMapper;
import com.kevin.mcp.mapper.PromotionScopeItemMapper;
import com.kevin.mcp.mapper.PromotionScopeStoreMapper;
import com.kevin.mcp.mapper.PurchaseOrderDetailMapper;
import com.kevin.mcp.mapper.PurchaseOrderMapper;
import com.kevin.mcp.mapper.StoreShiftMapper;
import com.kevin.mcp.mapper.StoreStockMapper;
import com.kevin.mcp.mapper.SupplierMapper;
import com.kevin.mcp.mapper.SysUserMapper;
import com.kevin.mcp.mapper.WarehouseReceiptMapper;
import com.kevin.mcp.mapper.WarehouseStockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 提供 ERP 领域数据库实体的内部查询 MCP Tool。
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Component
@RequiredArgsConstructor
public class ERPTool {
    private final FinanceJournalMapper financeJournalMapper;
    private final FinanceSettlementMapper financeSettlementMapper;
    private final FulfillmentTaskMapper fulfillmentTaskMapper;
    private final InventoryTransferMapper inventoryTransferMapper;
    private final ItemBrandMapper itemBrandMapper;
    private final ItemCategoryMapper itemCategoryMapper;
    private final ItemSkuMapper itemSkuMapper;
    private final MemberCouponMapper memberCouponMapper;
    private final MemberInfoMapper memberInfoMapper;
    private final MemberLevelMapper memberLevelMapper;
    private final MemberPointsLogMapper memberPointsLogMapper;
    private final OnlineOrderMapper onlineOrderMapper;
    private final OnlineOrderDetailMapper onlineOrderDetailMapper;
    private final OrgCompanyMapper orgCompanyMapper;
    private final OrgRegionMapper orgRegionMapper;
    private final OrgStoreMapper orgStoreMapper;
    private final PosOrderMapper posOrderMapper;
    private final PosOrderDetailMapper posOrderDetailMapper;
    private final PosPaymentMapper posPaymentMapper;
    private final PromotionRuleMapper promotionRuleMapper;
    private final PromotionScopeItemMapper promotionScopeItemMapper;
    private final PromotionScopeStoreMapper promotionScopeStoreMapper;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final PurchaseOrderDetailMapper purchaseOrderDetailMapper;
    private final StoreShiftMapper storeShiftMapper;
    private final StoreStockMapper storeStockMapper;
    private final SupplierMapper supplierMapper;
    private final SysUserMapper sysUserMapper;
    private final WarehouseReceiptMapper warehouseReceiptMapper;
    private final WarehouseStockMapper warehouseStockMapper;

    /**
     * 根据主键查询财务交易流水明细表（数据湖）记录。
     *
     * @param id 财务交易流水明细表（数据湖）ID
     * @return 匹配的财务交易流水明细表（数据湖）记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询财务交易流水明细表（数据湖）记录")
    public FinanceJournal getFinanceJournalById(
            @PrivateMcpToolParam(description = "财务交易流水明细表（数据湖）ID", required = true) Long id
    ) {
        return financeJournalMapper.selectById(id);
    }

    /**
     * 根据主键查询门店每日销售对账汇总表记录。
     *
     * @param id 门店每日销售对账汇总表ID
     * @return 匹配的门店每日销售对账汇总表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询门店每日销售对账汇总表记录")
    public FinanceSettlement getFinanceSettlementById(
            @PrivateMcpToolParam(description = "门店每日销售对账汇总表ID", required = true) Long id
    ) {
        return financeSettlementMapper.selectById(id);
    }

    /**
     * 根据主键查询门店O2O履约任务表记录。
     *
     * @param id 门店O2O履约任务表ID
     * @return 匹配的门店O2O履约任务表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询门店O2O履约任务表记录")
    public FulfillmentTask getFulfillmentTaskById(
            @PrivateMcpToolParam(description = "门店O2O履约任务表ID", required = true) Long id
    ) {
        return fulfillmentTaskMapper.selectById(id);
    }

    /**
     * 根据主键查询库存调拨单记录。
     *
     * @param id 库存调拨单ID
     * @return 匹配的库存调拨单记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询库存调拨单记录")
    public InventoryTransfer getInventoryTransferById(
            @PrivateMcpToolParam(description = "库存调拨单ID", required = true) Long id
    ) {
        return inventoryTransferMapper.selectById(id);
    }

    /**
     * 根据主键查询商品品牌表记录。
     *
     * @param id 商品品牌表ID
     * @return 匹配的商品品牌表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询商品品牌表记录")
    public ItemBrand getItemBrandById(
            @PrivateMcpToolParam(description = "商品品牌表ID", required = true) Long id
    ) {
        return itemBrandMapper.selectById(id);
    }

    /**
     * 根据主键查询商品分类表记录。
     *
     * @param id 商品分类表ID
     * @return 匹配的商品分类表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询商品分类表记录")
    public ItemCategory getItemCategoryById(
            @PrivateMcpToolParam(description = "商品分类表ID", required = true) Long id
    ) {
        return itemCategoryMapper.selectById(id);
    }

    /**
     * 根据主键查询SKU商品主数据表记录。
     *
     * @param id SKU商品主数据表ID
     * @return 匹配的SKU商品主数据表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询SKU商品主数据表记录")
    public ItemSku getItemSkuById(
            @PrivateMcpToolParam(description = "SKU商品主数据表ID", required = true) Long id
    ) {
        return itemSkuMapper.selectById(id);
    }

    /**
     * 根据主键查询会员优惠券持有表记录。
     *
     * @param id 会员优惠券持有表ID
     * @return 匹配的会员优惠券持有表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询会员优惠券持有表记录")
    public MemberCoupon getMemberCouponById(
            @PrivateMcpToolParam(description = "会员优惠券持有表ID", required = true) Long id
    ) {
        return memberCouponMapper.selectById(id);
    }

    /**
     * 根据主键查询会员个人信息表记录。
     *
     * @param id 会员个人信息表ID
     * @return 匹配的会员个人信息表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询会员个人信息表记录")
    public MemberInfo getMemberInfoById(
            @PrivateMcpToolParam(description = "会员个人信息表ID", required = true) Long id
    ) {
        return memberInfoMapper.selectById(id);
    }

    /**
     * 根据主键查询会员等级配置表记录。
     *
     * @param id 会员等级配置表ID
     * @return 匹配的会员等级配置表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询会员等级配置表记录")
    public MemberLevel getMemberLevelById(
            @PrivateMcpToolParam(description = "会员等级配置表ID", required = true) Long id
    ) {
        return memberLevelMapper.selectById(id);
    }

    /**
     * 根据主键查询会员积分流水日志记录。
     *
     * @param id 会员积分流水日志ID
     * @return 匹配的会员积分流水日志记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询会员积分流水日志记录")
    public MemberPointsLog getMemberPointsLogById(
            @PrivateMcpToolParam(description = "会员积分流水日志ID", required = true) Long id
    ) {
        return memberPointsLogMapper.selectById(id);
    }

    /**
     * 根据主键查询线上第三方订单主表记录。
     *
     * @param id 线上第三方订单主表ID
     * @return 匹配的线上第三方订单主表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询线上第三方订单主表记录")
    public OnlineOrder getOnlineOrderById(
            @PrivateMcpToolParam(description = "线上第三方订单主表ID", required = true) Long id
    ) {
        return onlineOrderMapper.selectById(id);
    }

    /**
     * 根据主键查询线上订单明细表记录。
     *
     * @param id 线上订单明细表ID
     * @return 匹配的线上订单明细表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询线上订单明细表记录")
    public OnlineOrderDetail getOnlineOrderDetailById(
            @PrivateMcpToolParam(description = "线上订单明细表ID", required = true) Long id
    ) {
        return onlineOrderDetailMapper.selectById(id);
    }

    /**
     * 根据主键查询公司信息表记录。
     *
     * @param id 公司信息表ID
     * @return 匹配的公司信息表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询公司信息表记录")
    public OrgCompany getOrgCompanyById(
            @PrivateMcpToolParam(description = "公司信息表ID", required = true) Long id
    ) {
        return orgCompanyMapper.selectById(id);
    }

    /**
     * 根据主键查询区域组织机构表记录。
     *
     * @param id 区域组织机构表ID
     * @return 匹配的区域组织机构表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询区域组织机构表记录")
    public OrgRegion getOrgRegionById(
            @PrivateMcpToolParam(description = "区域组织机构表ID", required = true) Long id
    ) {
        return orgRegionMapper.selectById(id);
    }

    /**
     * 根据主键查询门店主数据表记录。
     *
     * @param id 门店主数据表ID
     * @return 匹配的门店主数据表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询门店主数据表记录")
    public OrgStore getOrgStoreById(
            @PrivateMcpToolParam(description = "门店主数据表ID", required = true) Long id
    ) {
        return orgStoreMapper.selectById(id);
    }

    /**
     * 根据主键查询门店POS销售订单主表记录。
     *
     * @param id 门店POS销售订单主表ID
     * @return 匹配的门店POS销售订单主表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询门店POS销售订单主表记录")
    public PosOrder getPosOrderById(
            @PrivateMcpToolParam(description = "门店POS销售订单主表ID", required = true) Long id
    ) {
        return posOrderMapper.selectById(id);
    }

    /**
     * 根据主键查询POS销售订单明细表记录。
     *
     * @param id POS销售订单明细表ID
     * @return 匹配的POS销售订单明细表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询POS销售订单明细表记录")
    public PosOrderDetail getPosOrderDetailById(
            @PrivateMcpToolParam(description = "POS销售订单明细表ID", required = true) Long id
    ) {
        return posOrderDetailMapper.selectById(id);
    }

    /**
     * 根据主键查询POS支付记录表记录。
     *
     * @param id POS支付记录表ID
     * @return 匹配的POS支付记录表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询POS支付记录表记录")
    public PosPayment getPosPaymentById(
            @PrivateMcpToolParam(description = "POS支付记录表ID", required = true) Long id
    ) {
        return posPaymentMapper.selectById(id);
    }

    /**
     * 根据主键查询促销规则主表记录。
     *
     * @param id 促销规则主表ID
     * @return 匹配的促销规则主表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询促销规则主表记录")
    public PromotionRule getPromotionRuleById(
            @PrivateMcpToolParam(description = "促销规则主表ID", required = true) Long id
    ) {
        return promotionRuleMapper.selectById(id);
    }

    /**
     * 根据主键查询促销商品及赠品规则表记录。
     *
     * @param id 促销商品及赠品规则表ID
     * @return 匹配的促销商品及赠品规则表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询促销商品及赠品规则表记录")
    public PromotionScopeItem getPromotionScopeItemById(
            @PrivateMcpToolParam(description = "促销商品及赠品规则表ID", required = true) Long id
    ) {
        return promotionScopeItemMapper.selectById(id);
    }

    /**
     * 根据主键查询促销适用门店范围表记录。
     *
     * @param id 促销适用门店范围表ID
     * @return 匹配的促销适用门店范围表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询促销适用门店范围表记录")
    public PromotionScopeStore getPromotionScopeStoreById(
            @PrivateMcpToolParam(description = "促销适用门店范围表ID", required = true) Long id
    ) {
        return promotionScopeStoreMapper.selectById(id);
    }

    /**
     * 根据主键查询采购订单主表记录。
     *
     * @param id 采购订单主表ID
     * @return 匹配的采购订单主表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询采购订单主表记录")
    public PurchaseOrder getPurchaseOrderById(
            @PrivateMcpToolParam(description = "采购订单主表ID", required = true) Long id
    ) {
        return purchaseOrderMapper.selectById(id);
    }

    /**
     * 根据主键查询采购订单明细表记录。
     *
     * @param id 采购订单明细表ID
     * @return 匹配的采购订单明细表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询采购订单明细表记录")
    public PurchaseOrderDetail getPurchaseOrderDetailById(
            @PrivateMcpToolParam(description = "采购订单明细表ID", required = true) Long id
    ) {
        return purchaseOrderDetailMapper.selectById(id);
    }

    /**
     * 根据主键查询收银员班次表记录。
     *
     * @param id 收银员班次表ID
     * @return 匹配的收银员班次表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询收银员班次表记录")
    public StoreShift getStoreShiftById(
            @PrivateMcpToolParam(description = "收银员班次表ID", required = true) Long id
    ) {
        return storeShiftMapper.selectById(id);
    }

    /**
     * 根据主键查询门店库存实时表记录。
     *
     * @param id 门店库存实时表ID
     * @return 匹配的门店库存实时表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询门店库存实时表记录")
    public StoreStock getStoreStockById(
            @PrivateMcpToolParam(description = "门店库存实时表ID", required = true) Long id
    ) {
        return storeStockMapper.selectById(id);
    }

    /**
     * 根据主键查询供应商信息表记录。
     *
     * @param id 供应商信息表ID
     * @return 匹配的供应商信息表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询供应商信息表记录")
    public Supplier getSupplierById(
            @PrivateMcpToolParam(description = "供应商信息表ID", required = true) Long id
    ) {
        return supplierMapper.selectById(id);
    }

    /**
     * 根据主键查询系统用户表记录。
     *
     * @param id 系统用户表ID
     * @return 匹配的系统用户表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询系统用户表记录")
    public SysUser getSysUserById(
            @PrivateMcpToolParam(description = "系统用户表ID", required = true) Long id
    ) {
        return sysUserMapper.selectById(id);
    }

    /**
     * 根据主键查询总仓入库单（库存流水）记录。
     *
     * @param id 总仓入库单（库存流水）ID
     * @return 匹配的总仓入库单（库存流水）记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询总仓入库单（库存流水）记录")
    public WarehouseReceipt getWarehouseReceiptById(
            @PrivateMcpToolParam(description = "总仓入库单（库存流水）ID", required = true) Long id
    ) {
        return warehouseReceiptMapper.selectById(id);
    }

    /**
     * 根据主键查询总仓库存实时表记录。
     *
     * @param id 总仓库存实时表ID
     * @return 匹配的总仓库存实时表记录；不存在时返回 null
     */
    @PrivateMcpTool(description = "根据主键查询总仓库存实时表记录")
    public WarehouseStock getWarehouseStockById(
            @PrivateMcpToolParam(description = "总仓库存实时表ID", required = true) Long id
    ) {
        return warehouseStockMapper.selectById(id);
    }

    /**
     * 根据公司查询其下属区域列表。
     *
     * @param companyId 公司ID
     * @return 匹配的区域列表
     */
    @PrivateMcpTool(description = "根据公司查询下属区域列表")
    public List<OrgRegion> listOrgRegionsByCompanyId(
            @PrivateMcpToolParam(description = "公司ID", required = true) Long companyId
    ) {
        OrgRegion condition = new OrgRegion();
        condition.setCompanyId(companyId);
        return orgRegionMapper.selectByCondition(condition);
    }

    /**
     * 根据公司查询其下属门店列表。
     *
     * @param companyId 公司ID
     * @return 匹配的门店列表
     */
    @PrivateMcpTool(description = "根据公司查询下属门店列表")
    public List<OrgStore> listOrgStoresByCompanyId(
            @PrivateMcpToolParam(description = "公司ID", required = true) Long companyId
    ) {
        OrgStore condition = new OrgStore();
        condition.setCompanyId(companyId);
        return orgStoreMapper.selectByCondition(condition);
    }

    /**
     * 根据区域查询其下属门店列表。
     *
     * @param regionId 区域ID
     * @return 匹配的门店列表
     */
    @PrivateMcpTool(description = "根据区域查询下属门店列表")
    public List<OrgStore> listOrgStoresByRegionId(
            @PrivateMcpToolParam(description = "区域ID", required = true) Long regionId
    ) {
        OrgStore condition = new OrgStore();
        condition.setRegionId(regionId);
        return orgStoreMapper.selectByCondition(condition);
    }

    /**
     * 根据门店查询门店用户列表。
     *
     * @param storeId 门店ID
     * @return 匹配的系统用户列表
     */
    @PrivateMcpTool(description = "根据门店查询系统用户列表")
    public List<SysUser> listSysUsersByStoreId(
            @PrivateMcpToolParam(description = "门店ID", required = true) Long storeId
    ) {
        SysUser condition = new SysUser();
        condition.setStoreId(storeId);
        return sysUserMapper.selectByCondition(condition);
    }

    /**
     * 根据商品分类查询 SKU 列表。
     *
     * @param categoryId 商品分类ID
     * @return 匹配的 SKU 列表
     */
    @PrivateMcpTool(description = "根据商品分类查询SKU列表")
    public List<ItemSku> listItemSkusByCategoryId(
            @PrivateMcpToolParam(description = "商品分类ID", required = true) Long categoryId
    ) {
        ItemSku condition = new ItemSku();
        condition.setCategoryId(categoryId);
        return itemSkuMapper.selectByCondition(condition);
    }

    /**
     * 根据商品品牌查询 SKU 列表。
     *
     * @param brandId 商品品牌ID
     * @return 匹配的 SKU 列表
     */
    @PrivateMcpTool(description = "根据商品品牌查询SKU列表")
    public List<ItemSku> listItemSkusByBrandId(
            @PrivateMcpToolParam(description = "商品品牌ID", required = true) Long brandId
    ) {
        ItemSku condition = new ItemSku();
        condition.setBrandId(brandId);
        return itemSkuMapper.selectByCondition(condition);
    }

    /**
     * 根据供应商查询采购订单列表。
     *
     * @param supplierId 供应商ID
     * @return 匹配的采购订单列表
     */
    @PrivateMcpTool(description = "根据供应商查询采购订单列表")
    public List<PurchaseOrder> listPurchaseOrdersBySupplierId(
            @PrivateMcpToolParam(description = "供应商ID", required = true) Long supplierId
    ) {
        PurchaseOrder condition = new PurchaseOrder();
        condition.setSupplierId(supplierId);
        return purchaseOrderMapper.selectByCondition(condition);
    }

    /**
     * 根据采购订单查询采购明细列表。
     *
     * @param poId 采购订单ID
     * @return 匹配的采购订单明细列表
     */
    @PrivateMcpTool(description = "根据采购订单查询采购明细列表")
    public List<PurchaseOrderDetail> listPurchaseOrderDetailsByPoId(
            @PrivateMcpToolParam(description = "采购订单ID", required = true) Long poId
    ) {
        PurchaseOrderDetail condition = new PurchaseOrderDetail();
        condition.setPoId(poId);
        return purchaseOrderDetailMapper.selectByCondition(condition);
    }

    /**
     * 根据采购订单查询入库记录列表。
     *
     * @param poId 采购订单ID
     * @return 匹配的入库记录列表
     */
    @PrivateMcpTool(description = "根据采购订单查询入库记录列表")
    public List<WarehouseReceipt> listWarehouseReceiptsByPoId(
            @PrivateMcpToolParam(description = "采购订单ID", required = true) Long poId
    ) {
        WarehouseReceipt condition = new WarehouseReceipt();
        condition.setPoId(poId);
        return warehouseReceiptMapper.selectByCondition(condition);
    }

    /**
     * 根据 SKU 查询总仓库存列表。
     *
     * @param skuId SKU ID
     * @return 匹配的总仓库存列表
     */
    @PrivateMcpTool(description = "根据SKU查询总仓库存列表")
    public List<WarehouseStock> listWarehouseStocksBySkuId(
            @PrivateMcpToolParam(description = "SKU ID", required = true) Long skuId
    ) {
        WarehouseStock condition = new WarehouseStock();
        condition.setSkuId(skuId);
        return warehouseStockMapper.selectByCondition(condition);
    }

    /**
     * 根据门店查询门店库存列表。
     *
     * @param storeId 门店ID
     * @return 匹配的门店库存列表
     */
    @PrivateMcpTool(description = "根据门店查询门店库存列表")
    public List<StoreStock> listStoreStocksByStoreId(
            @PrivateMcpToolParam(description = "门店ID", required = true) Long storeId
    ) {
        StoreStock condition = new StoreStock();
        condition.setStoreId(storeId);
        return storeStockMapper.selectByCondition(condition);
    }

    /**
     * 根据 SKU 查询门店库存列表。
     *
     * @param skuId SKU ID
     * @return 匹配的门店库存列表
     */
    @PrivateMcpTool(description = "根据SKU查询门店库存列表")
    public List<StoreStock> listStoreStocksBySkuId(
            @PrivateMcpToolParam(description = "SKU ID", required = true) Long skuId
    ) {
        StoreStock condition = new StoreStock();
        condition.setSkuId(skuId);
        return storeStockMapper.selectByCondition(condition);
    }

    /**
     * 根据会员查询优惠券列表。
     *
     * @param memberId 会员ID
     * @return 匹配的会员优惠券列表
     */
    @PrivateMcpTool(description = "根据会员查询优惠券列表")
    public List<MemberCoupon> listMemberCouponsByMemberId(
            @PrivateMcpToolParam(description = "会员ID", required = true) Long memberId
    ) {
        MemberCoupon condition = new MemberCoupon();
        condition.setMemberId(memberId);
        return memberCouponMapper.selectByCondition(condition);
    }

    /**
     * 根据会员查询积分流水列表。
     *
     * @param memberId 会员ID
     * @return 匹配的会员积分流水列表
     */
    @PrivateMcpTool(description = "根据会员查询积分流水列表")
    public List<MemberPointsLog> listMemberPointsLogsByMemberId(
            @PrivateMcpToolParam(description = "会员ID", required = true) Long memberId
    ) {
        MemberPointsLog condition = new MemberPointsLog();
        condition.setMemberId(memberId);
        return memberPointsLogMapper.selectByCondition(condition);
    }

    /**
     * 根据会员等级查询会员列表。
     *
     * @param levelId 会员等级ID
     * @return 匹配的会员列表
     */
    @PrivateMcpTool(description = "根据会员等级查询会员列表")
    public List<MemberInfo> listMembersByLevelId(
            @PrivateMcpToolParam(description = "会员等级ID", required = true) Long levelId
    ) {
        MemberInfo condition = new MemberInfo();
        condition.setLevelId(levelId);
        return memberInfoMapper.selectByCondition(condition);
    }

    /**
     * 根据门店查询 POS 订单列表。
     *
     * @param storeId 门店ID
     * @return 匹配的 POS 订单列表
     */
    @PrivateMcpTool(description = "根据门店查询POS订单列表")
    public List<PosOrder> listPosOrdersByStoreId(
            @PrivateMcpToolParam(description = "门店ID", required = true) Long storeId
    ) {
        PosOrder condition = new PosOrder();
        condition.setStoreId(storeId);
        return posOrderMapper.selectByCondition(condition);
    }

    /**
     * 根据 POS 订单查询订单明细列表。
     *
     * @param orderId POS 订单ID
     * @return 匹配的 POS 订单明细列表
     */
    @PrivateMcpTool(description = "根据POS订单查询订单明细列表")
    public List<PosOrderDetail> listPosOrderDetailsByOrderId(
            @PrivateMcpToolParam(description = "POS订单ID", required = true) Long orderId
    ) {
        PosOrderDetail condition = new PosOrderDetail();
        condition.setOrderId(orderId);
        return posOrderDetailMapper.selectByCondition(condition);
    }

    /**
     * 根据 POS 订单查询支付记录列表。
     *
     * @param orderId POS 订单ID
     * @return 匹配的 POS 支付记录列表
     */
    @PrivateMcpTool(description = "根据POS订单查询支付记录列表")
    public List<PosPayment> listPosPaymentsByOrderId(
            @PrivateMcpToolParam(description = "POS订单ID", required = true) Long orderId
    ) {
        PosPayment condition = new PosPayment();
        condition.setOrderId(orderId);
        return posPaymentMapper.selectByCondition(condition);
    }

    /**
     * 根据收银班次查询 POS 订单列表。
     *
     * @param shiftId 收银班次ID
     * @return 匹配的 POS 订单列表
     */
    @PrivateMcpTool(description = "根据收银班次查询POS订单列表")
    public List<PosOrder> listPosOrdersByShiftId(
            @PrivateMcpToolParam(description = "收银班次ID", required = true) Long shiftId
    ) {
        PosOrder condition = new PosOrder();
        condition.setShiftId(shiftId);
        return posOrderMapper.selectByCondition(condition);
    }

    /**
     * 根据门店查询线上订单列表。
     *
     * @param storeId 门店ID
     * @return 匹配的线上订单列表
     */
    @PrivateMcpTool(description = "根据门店查询线上订单列表")
    public List<OnlineOrder> listOnlineOrdersByStoreId(
            @PrivateMcpToolParam(description = "门店ID", required = true) Long storeId
    ) {
        OnlineOrder condition = new OnlineOrder();
        condition.setStoreId(storeId);
        return onlineOrderMapper.selectByCondition(condition);
    }

    /**
     * 根据线上订单查询订单明细列表。
     *
     * @param orderId 线上订单ID
     * @return 匹配的线上订单明细列表
     */
    @PrivateMcpTool(description = "根据线上订单查询订单明细列表")
    public List<OnlineOrderDetail> listOnlineOrderDetailsByOrderId(
            @PrivateMcpToolParam(description = "线上订单ID", required = true) Long orderId
    ) {
        OnlineOrderDetail condition = new OnlineOrderDetail();
        condition.setOrderId(orderId);
        return onlineOrderDetailMapper.selectByCondition(condition);
    }

    /**
     * 根据线上订单查询履约任务列表。
     *
     * @param onlineOrderId 线上订单ID
     * @return 匹配的履约任务列表
     */
    @PrivateMcpTool(description = "根据线上订单查询履约任务列表")
    public List<FulfillmentTask> listFulfillmentTasksByOnlineOrderId(
            @PrivateMcpToolParam(description = "线上订单ID", required = true) Long onlineOrderId
    ) {
        FulfillmentTask condition = new FulfillmentTask();
        condition.setOnlineOrderId(onlineOrderId);
        return fulfillmentTaskMapper.selectByCondition(condition);
    }

    /**
     * 根据促销规则查询适用商品范围列表。
     *
     * @param promotionId 促销规则ID
     * @return 匹配的促销商品范围列表
     */
    @PrivateMcpTool(description = "根据促销规则查询适用商品范围列表")
    public List<PromotionScopeItem> listPromotionScopeItemsByPromotionId(
            @PrivateMcpToolParam(description = "促销规则ID", required = true) Long promotionId
    ) {
        PromotionScopeItem condition = new PromotionScopeItem();
        condition.setPromotionId(promotionId);
        return promotionScopeItemMapper.selectByCondition(condition);
    }

    /**
     * 根据促销规则查询适用门店范围列表。
     *
     * @param promotionId 促销规则ID
     * @return 匹配的促销门店范围列表
     */
    @PrivateMcpTool(description = "根据促销规则查询适用门店范围列表")
    public List<PromotionScopeStore> listPromotionScopeStoresByPromotionId(
            @PrivateMcpToolParam(description = "促销规则ID", required = true) Long promotionId
    ) {
        PromotionScopeStore condition = new PromotionScopeStore();
        condition.setPromotionId(promotionId);
        return promotionScopeStoreMapper.selectByCondition(condition);
    }

    /**
     * 根据门店查询财务流水列表。
     *
     * @param storeId 门店ID
     * @return 匹配的财务流水列表
     */
    @PrivateMcpTool(description = "根据门店查询财务流水列表")
    public List<FinanceJournal> listFinanceJournalsByStoreId(
            @PrivateMcpToolParam(description = "门店ID", required = true) Long storeId
    ) {
        FinanceJournal condition = new FinanceJournal();
        condition.setStoreId(storeId);
        return financeJournalMapper.selectByCondition(condition);
    }

    /**
     * 根据门店查询财务结算列表。
     *
     * @param storeId 门店ID
     * @return 匹配的财务结算列表
     */
    @PrivateMcpTool(description = "根据门店查询财务结算列表")
    public List<FinanceSettlement> listFinanceSettlementsByStoreId(
            @PrivateMcpToolParam(description = "门店ID", required = true) Long storeId
    ) {
        FinanceSettlement condition = new FinanceSettlement();
        condition.setStoreId(storeId);
        return financeSettlementMapper.selectByCondition(condition);
    }

    /**
     * 根据父级区域查询下级区域列表。
     *
     * @param parentId 父级区域ID
     * @return 匹配的下级区域列表
     */
    @PrivateMcpTool(description = "根据父级区域查询下级区域列表")
    public List<OrgRegion> listOrgRegionsByParentId(
            @PrivateMcpToolParam(description = "父级区域ID", required = true) Long parentId
    ) {
        OrgRegion condition = new OrgRegion();
        condition.setParentId(parentId);
        return orgRegionMapper.selectByCondition(condition);
    }

    /**
     * 根据目标仓库查询采购订单列表。
     *
     * @param warehouseId 目标仓库ID
     * @return 匹配的采购订单列表
     */
    @PrivateMcpTool(description = "根据目标仓库查询采购订单列表")
    public List<PurchaseOrder> listPurchaseOrdersByWarehouseId(
            @PrivateMcpToolParam(description = "目标仓库ID", required = true) Long warehouseId
    ) {
        PurchaseOrder condition = new PurchaseOrder();
        condition.setWarehouseId(warehouseId);
        return purchaseOrderMapper.selectByCondition(condition);
    }

    /**
     * 根据仓库查询入库记录列表。
     *
     * @param warehouseId 仓库ID
     * @return 匹配的入库记录列表
     */
    @PrivateMcpTool(description = "根据仓库查询入库记录列表")
    public List<WarehouseReceipt> listWarehouseReceiptsByWarehouseId(
            @PrivateMcpToolParam(description = "仓库ID", required = true) Long warehouseId
    ) {
        WarehouseReceipt condition = new WarehouseReceipt();
        condition.setWarehouseId(warehouseId);
        return warehouseReceiptMapper.selectByCondition(condition);
    }

    /**
     * 根据仓库查询总仓库存列表。
     *
     * @param warehouseId 仓库ID
     * @return 匹配的总仓库存列表
     */
    @PrivateMcpTool(description = "根据仓库查询总仓库存列表")
    public List<WarehouseStock> listWarehouseStocksByWarehouseId(
            @PrivateMcpToolParam(description = "仓库ID", required = true) Long warehouseId
    ) {
        WarehouseStock condition = new WarehouseStock();
        condition.setWarehouseId(warehouseId);
        return warehouseStockMapper.selectByCondition(condition);
    }

    /**
     * 根据 SKU 查询采购明细列表。
     *
     * @param skuId SKU ID
     * @return 匹配的采购明细列表
     */
    @PrivateMcpTool(description = "根据SKU查询采购明细列表")
    public List<PurchaseOrderDetail> listPurchaseOrderDetailsBySkuId(
            @PrivateMcpToolParam(description = "SKU ID", required = true) Long skuId
    ) {
        PurchaseOrderDetail condition = new PurchaseOrderDetail();
        condition.setSkuId(skuId);
        return purchaseOrderDetailMapper.selectByCondition(condition);
    }

    /**
     * 根据 SKU 查询入库记录列表。
     *
     * @param skuId SKU ID
     * @return 匹配的入库记录列表
     */
    @PrivateMcpTool(description = "根据SKU查询入库记录列表")
    public List<WarehouseReceipt> listWarehouseReceiptsBySkuId(
            @PrivateMcpToolParam(description = "SKU ID", required = true) Long skuId
    ) {
        WarehouseReceipt condition = new WarehouseReceipt();
        condition.setSkuId(skuId);
        return warehouseReceiptMapper.selectByCondition(condition);
    }

    /**
     * 根据 SKU 查询库存调拨列表。
     *
     * @param skuId SKU ID
     * @return 匹配的库存调拨列表
     */
    @PrivateMcpTool(description = "根据SKU查询库存调拨列表")
    public List<InventoryTransfer> listInventoryTransfersBySkuId(
            @PrivateMcpToolParam(description = "SKU ID", required = true) Long skuId
    ) {
        InventoryTransfer condition = new InventoryTransfer();
        condition.setSkuId(skuId);
        return inventoryTransferMapper.selectByCondition(condition);
    }

    /**
     * 根据目标门店查询库存调拨列表。
     *
     * @param storeId 目标门店ID
     * @return 匹配的库存调拨列表
     */
    @PrivateMcpTool(description = "根据目标门店查询库存调拨列表")
    public List<InventoryTransfer> listInventoryTransfersByStoreId(
            @PrivateMcpToolParam(description = "目标门店ID", required = true) Long storeId
    ) {
        InventoryTransfer condition = new InventoryTransfer();
        condition.setToStoreId(storeId);
        return inventoryTransferMapper.selectByCondition(condition);
    }

    /**
     * 根据门店查询收银班次列表。
     *
     * @param storeId 门店ID
     * @return 匹配的收银班次列表
     */
    @PrivateMcpTool(description = "根据门店查询收银班次列表")
    public List<StoreShift> listStoreShiftsByStoreId(
            @PrivateMcpToolParam(description = "门店ID", required = true) Long storeId
    ) {
        StoreShift condition = new StoreShift();
        condition.setStoreId(storeId);
        return storeShiftMapper.selectByCondition(condition);
    }

    /**
     * 根据收银员查询收银班次列表。
     *
     * @param cashierId 收银员用户ID
     * @return 匹配的收银班次列表
     */
    @PrivateMcpTool(description = "根据收银员查询收银班次列表")
    public List<StoreShift> listStoreShiftsByCashierId(
            @PrivateMcpToolParam(description = "收银员用户ID", required = true) Long cashierId
    ) {
        StoreShift condition = new StoreShift();
        condition.setCashierId(cashierId);
        return storeShiftMapper.selectByCondition(condition);
    }

    /**
     * 根据收银员查询 POS 订单列表。
     *
     * @param cashierId 收银员用户ID
     * @return 匹配的 POS 订单列表
     */
    @PrivateMcpTool(description = "根据收银员查询POS订单列表")
    public List<PosOrder> listPosOrdersByCashierId(
            @PrivateMcpToolParam(description = "收银员用户ID", required = true) Long cashierId
    ) {
        PosOrder condition = new PosOrder();
        condition.setCashierId(cashierId);
        return posOrderMapper.selectByCondition(condition);
    }

    /**
     * 根据会员查询 POS 订单列表。
     *
     * @param memberId 会员ID
     * @return 匹配的 POS 订单列表
     */
    @PrivateMcpTool(description = "根据会员查询POS订单列表")
    public List<PosOrder> listPosOrdersByMemberId(
            @PrivateMcpToolParam(description = "会员ID", required = true) Long memberId
    ) {
        PosOrder condition = new PosOrder();
        condition.setMemberId(memberId);
        return posOrderMapper.selectByCondition(condition);
    }

    /**
     * 根据会员查询线上订单列表。
     *
     * @param memberId 会员ID
     * @return 匹配的线上订单列表
     */
    @PrivateMcpTool(description = "根据会员查询线上订单列表")
    public List<OnlineOrder> listOnlineOrdersByMemberId(
            @PrivateMcpToolParam(description = "会员ID", required = true) Long memberId
    ) {
        OnlineOrder condition = new OnlineOrder();
        condition.setMemberId(memberId);
        return onlineOrderMapper.selectByCondition(condition);
    }

    /**
     * 根据门店查询线上订单履约任务列表。
     *
     * @param storeId 门店ID
     * @return 匹配的履约任务列表
     */
    @PrivateMcpTool(description = "根据门店查询线上订单履约任务列表")
    public List<FulfillmentTask> listFulfillmentTasksByStoreId(
            @PrivateMcpToolParam(description = "门店ID", required = true) Long storeId
    ) {
        FulfillmentTask condition = new FulfillmentTask();
        condition.setStoreId(storeId);
        return fulfillmentTaskMapper.selectByCondition(condition);
    }

    /**
     * 根据操作员查询履约任务列表。
     *
     * @param operatorId 操作员用户ID
     * @return 匹配的履约任务列表
     */
    @PrivateMcpTool(description = "根据操作员查询履约任务列表")
    public List<FulfillmentTask> listFulfillmentTasksByOperatorId(
            @PrivateMcpToolParam(description = "操作员用户ID", required = true) Long operatorId
    ) {
        FulfillmentTask condition = new FulfillmentTask();
        condition.setOperatorId(operatorId);
        return fulfillmentTaskMapper.selectByCondition(condition);
    }

    /**
     * 根据门店查询适用促销门店范围列表。
     *
     * @param storeId 门店ID
     * @return 匹配的促销门店范围列表
     */
    @PrivateMcpTool(description = "根据门店查询适用促销范围列表")
    public List<PromotionScopeStore> listPromotionScopeStoresByStoreId(
            @PrivateMcpToolParam(description = "门店ID", required = true) Long storeId
    ) {
        PromotionScopeStore condition = new PromotionScopeStore();
        condition.setStoreId(storeId);
        return promotionScopeStoreMapper.selectByCondition(condition);
    }

    /**
     * 根据 SKU 查询适用促销商品范围列表。
     *
     * @param skuId SKU ID
     * @return 匹配的促销商品范围列表
     */
    @PrivateMcpTool(description = "根据SKU查询适用促销商品范围列表")
    public List<PromotionScopeItem> listPromotionScopeItemsBySkuId(
            @PrivateMcpToolParam(description = "SKU ID", required = true) Long skuId
    ) {
        PromotionScopeItem condition = new PromotionScopeItem();
        condition.setSkuId(skuId);
        return promotionScopeItemMapper.selectByCondition(condition);
    }

    /**
     * 根据来源单号查询财务流水列表。
     *
     * @param sourceNo 来源单号
     * @return 匹配的财务流水列表
     */
    @PrivateMcpTool(description = "根据来源单号查询财务流水列表")
    public List<FinanceJournal> listFinanceJournalsBySourceNo(
            @PrivateMcpToolParam(description = "来源单号", required = true) String sourceNo
    ) {
        FinanceJournal condition = new FinanceJournal();
        condition.setSourceNo(sourceNo);
        return financeJournalMapper.selectByCondition(condition);
    }
}
