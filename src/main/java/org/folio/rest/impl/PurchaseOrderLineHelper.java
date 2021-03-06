package org.folio.rest.impl;

import static io.vertx.core.json.JsonObject.mapFrom;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static me.escoffier.vertx.completablefuture.VertxCompletableFuture.supplyBlockingAsync;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.folio.orders.utils.ErrorCodes.INCORRECT_FUND_DISTRIBUTION_TOTAL;
import static org.folio.orders.utils.ErrorCodes.PIECES_TO_BE_CREATED;
import static org.folio.orders.utils.ErrorCodes.PIECES_TO_BE_DELETED;
import static org.folio.orders.utils.HelperUtils.URL_WITH_LANG_PARAM;
import static org.folio.orders.utils.HelperUtils.calculateEstimatedPrice;
import static org.folio.orders.utils.HelperUtils.calculateInventoryItemsQuantity;
import static org.folio.orders.utils.HelperUtils.calculatePiecesQuantityWithoutLocation;
import static org.folio.orders.utils.HelperUtils.calculateTotalLocationQuantity;
import static org.folio.orders.utils.HelperUtils.calculateTotalQuantity;
import static org.folio.orders.utils.HelperUtils.collectResultsOnSuccess;
import static org.folio.orders.utils.HelperUtils.combineCqlExpressions;
import static org.folio.orders.utils.HelperUtils.deletePoLine;
import static org.folio.orders.utils.HelperUtils.encodeQuery;
import static org.folio.orders.utils.HelperUtils.getPoLineById;
import static org.folio.orders.utils.HelperUtils.getPoLineLimit;
import static org.folio.orders.utils.HelperUtils.getPurchaseOrderById;
import static org.folio.orders.utils.HelperUtils.groupLocationsById;
import static org.folio.orders.utils.HelperUtils.handleGetRequest;
import static org.folio.orders.utils.HelperUtils.inventoryUpdateNotRequired;
import static org.folio.orders.utils.HelperUtils.numOfLocationsByPoLineIdAndLocationId;
import static org.folio.orders.utils.HelperUtils.numOfPiecesByPoLineAndLocationId;
import static org.folio.orders.utils.HelperUtils.operateOnObject;
import static org.folio.orders.utils.HelperUtils.verifyProtectedFieldsChanged;
import static org.folio.orders.utils.ProtectedOperationType.DELETE;
import static org.folio.orders.utils.ProtectedOperationType.UPDATE;
import static org.folio.orders.utils.ResourcePathResolver.ALERTS;
import static org.folio.orders.utils.ResourcePathResolver.ORDER_LINES;
import static org.folio.orders.utils.ResourcePathResolver.PIECES;
import static org.folio.orders.utils.ResourcePathResolver.PO_LINES;
import static org.folio.orders.utils.ResourcePathResolver.PO_LINE_NUMBER;
import static org.folio.orders.utils.ResourcePathResolver.REPORTING_CODES;
import static org.folio.orders.utils.ResourcePathResolver.resourceByIdPath;
import static org.folio.orders.utils.ResourcePathResolver.resourcesPath;
import static org.folio.orders.utils.validators.CompositePoLineValidationUtil.validatePoLine;
import static org.folio.rest.impl.PurchaseOrderHelper.ENCUMBRANCE_POST_ENDPOINT;
import static org.folio.rest.jaxrs.model.CompositePurchaseOrder.WorkflowStatus.OPEN;
import static org.folio.rest.jaxrs.model.CompositePurchaseOrder.WorkflowStatus.PENDING;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.money.MonetaryAmount;
import javax.ws.rs.core.Response;

import io.vertx.core.Future;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.folio.models.EncumbranceRelationsHolder;
import org.folio.models.EncumbrancesProcessingHolder;
import org.folio.models.PoLineFundHolder;
import org.folio.orders.events.handlers.MessageAddress;
import org.folio.orders.rest.exceptions.HttpException;
import org.folio.orders.rest.exceptions.InventoryException;
import org.folio.orders.utils.ErrorCodes;
import org.folio.orders.utils.HelperUtils;
import org.folio.orders.utils.POLineProtectedFields;
import org.folio.orders.utils.ProtectedOperationType;
import org.folio.rest.acq.model.Piece;
import org.folio.rest.acq.model.PieceCollection;
import org.folio.rest.acq.model.SequenceNumber;
import org.folio.rest.acq.model.finance.Transaction;
import org.folio.rest.jaxrs.model.Alert;
import org.folio.rest.jaxrs.model.CompositePoLine;
import org.folio.rest.jaxrs.model.CompositePoLine.OrderFormat;
import org.folio.rest.jaxrs.model.CompositePoLine.ReceiptStatus;
import org.folio.rest.jaxrs.model.CompositePurchaseOrder;
import org.folio.rest.jaxrs.model.Cost;
import org.folio.rest.jaxrs.model.Eresource;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.FundDistribution;
import org.folio.rest.jaxrs.model.Location;
import org.folio.rest.jaxrs.model.Parameter;
import org.folio.rest.jaxrs.model.Physical;
import org.folio.rest.jaxrs.model.PoLine;
import org.folio.rest.jaxrs.model.PoLineCollection;
import org.folio.rest.jaxrs.model.ProductId;
import org.folio.rest.jaxrs.model.ReportingCode;
import org.folio.rest.jaxrs.model.Title;
import org.folio.rest.tools.client.interfaces.HttpClientInterface;

import io.vertx.core.Context;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import me.escoffier.vertx.completablefuture.VertxCompletableFuture;
import one.util.streamex.StreamEx;
import org.javamoney.moneta.Money;
import org.javamoney.moneta.function.MonetaryOperators;

class PurchaseOrderLineHelper extends AbstractHelper {

  private static final String ISBN = "ISBN";
  private static final String PURCHASE_ORDER_ID = "purchaseOrderId";
  private static final String GET_PO_LINES_BY_QUERY = resourcesPath(PO_LINES) + SEARCH_PARAMS;
  private static final String GET_ORDER_LINES_BY_QUERY = resourcesPath(ORDER_LINES) + SEARCH_PARAMS;
  private static final String LOOKUP_PIECES_ENDPOINT = resourcesPath(PIECES) + "?query=poLineId==%s&limit=%d&lang=%s";
  private static final String PO_LINE_NUMBER_ENDPOINT = resourcesPath(PO_LINE_NUMBER) + "?" + PURCHASE_ORDER_ID + "=";
  private static final Pattern PO_LINE_NUMBER_PATTERN = Pattern.compile("([a-zA-Z0-9]{1,16}-)([0-9]{1,3})");
  private static final String CREATE_INVENTORY = "createInventory";
  private static final String ERESOURCE = "eresource";
  private static final String PHYSICAL = "physical";
  private static final String OTHER = "other";
  private static final String DASH_SEPARATOR = "-";
  public static final String QUERY_BY_PO_LINE_ID = "poLineId==";

  private final InventoryHelper inventoryHelper;
  private final ProtectionHelper protectionHelper;
  private final PiecesHelper piecesHelper;
  private final FinanceHelper financeHelper;

  public PurchaseOrderLineHelper(HttpClientInterface httpClient, Map<String, String> okapiHeaders, Context ctx, String lang) {
    super(httpClient, okapiHeaders, ctx, lang);
    inventoryHelper = new InventoryHelper(httpClient, okapiHeaders, ctx, lang);
    protectionHelper = new ProtectionHelper(httpClient, okapiHeaders, ctx, lang);
    piecesHelper = new PiecesHelper(httpClient, okapiHeaders, ctx, lang);
    financeHelper = new FinanceHelper(httpClient, okapiHeaders, ctx, lang);
  }

  public PurchaseOrderLineHelper(Map<String, String> okapiHeaders, Context ctx, String lang) {
    this(getHttpClient(okapiHeaders), okapiHeaders, ctx, lang);
  }

  CompletableFuture<PoLineCollection> getPoLines(int limit, int offset, String query, String path) {
    CompletableFuture<PoLineCollection> future = new VertxCompletableFuture<>(ctx);
    try {
      String queryParam = isEmpty(query) ? EMPTY : "&query=" + encodeQuery(query, logger);
      String endpoint = String.format(path, limit, offset, queryParam, lang);
      handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger)
        .thenAccept(jsonOrderLines -> {
          if (logger.isInfoEnabled()) {
            logger.info("Successfully retrieved order lines: {}", jsonOrderLines.encodePrettily());
          }
          future.complete(jsonOrderLines.mapTo(PoLineCollection.class));
        })
        .exceptionally(t -> {
          future.completeExceptionally(t);
          return null;
        });
    } catch (Exception e) {
      future.completeExceptionally(e);
    }
    return future;
  }

  /**
   * This method is used for all internal calls to fetch PO lines without or with
   * queries that search/filter on fields present in po_line
   *
   * @param limit Limit the number of elements returned in the response
   * @param offset Skip over a number of elements by specifying an offset value for the query
   * @param query A query expressed as a CQL string (see dev.folio.org/reference/glossary#cql) using valid searchable fields.
   * @return Completable future which holds {@link PoLineCollection}
   */
  public CompletableFuture<PoLineCollection> getPoLines(int limit, int offset, String query) {
    return getPoLines(limit, offset, query, GET_PO_LINES_BY_QUERY);
  }

  /**
   * This method queries a view, to limit performance implications this method
   * must be used only when there is a necessity to search/filter on the
   * Composite Purchase Order fields
   *
   * @param limit Limit the number of elements returned in the response
   * @param offset Skip over a number of elements by specifying an offset value for the query
   * @param query A query expressed as a CQL string (see dev.folio.org/reference/glossary#cql) using valid searchable fields.
   * @return Completable future which holds {@link PoLineCollection} on success or an exception on any error
   */
  public CompletableFuture<PoLineCollection> getOrderLines(int limit, int offset, String query) {
    AcquisitionsUnitsHelper acqUnitsHelper = new AcquisitionsUnitsHelper(httpClient, okapiHeaders, ctx, lang);
    return acqUnitsHelper.buildAcqUnitsCqlExprToSearchRecords().thenCompose(acqUnitsCqlExpr -> {
      if (isEmpty(query)) {
        return getPoLines(limit, offset, acqUnitsCqlExpr);
      }
      return getPoLines(limit, offset, combineCqlExpressions("and", acqUnitsCqlExpr, query), GET_ORDER_LINES_BY_QUERY);
    });
  }

  /**
   * Creates PO Line if its content is valid and all restriction checks passed
   * @param compPOL {@link CompositePoLine} to be created
   * @return completable future which might hold {@link CompositePoLine} on success, {@code null} if validation fails or an exception if any issue happens
   */
  public CompletableFuture<CompositePoLine> createPoLine(CompositePoLine compPOL) {
    // Validate PO Line content and retrieve order only if this operation is allowed
    return setTenantDefaultCreateInventoryValues(compPOL)
      .thenCompose(v -> validateNewPoLine(compPOL))
      .thenCompose(isValid -> {
        if (isValid) {
          return getCompositePurchaseOrder(compPOL.getPurchaseOrderId())
            // The PO Line can be created only for order in Pending state
            .thenApply(this::validateOrderState)
            .thenCompose(po -> protectionHelper.isOperationRestricted(po.getAcqUnitIds(), ProtectedOperationType.CREATE).thenApply(vVoid -> po))
            .thenCompose(po -> createPoLine(compPOL, po));
        } else {
          return completedFuture(null);
        }
      });
  }

  public void makePoLinesPending(List<CompositePoLine> compositePoLines) {
    compositePoLines.forEach(poLine -> {
      if (poLine.getPaymentStatus() == CompositePoLine.PaymentStatus.AWAITING_PAYMENT) {
        poLine.setPaymentStatus(CompositePoLine.PaymentStatus.PENDING);
      }
      if (poLine.getReceiptStatus() == CompositePoLine.ReceiptStatus.AWAITING_RECEIPT) {
        poLine.setReceiptStatus(CompositePoLine.ReceiptStatus.PENDING);
      }
    });
  }

  private CompositePurchaseOrder validateOrderState(CompositePurchaseOrder po) {
    CompositePurchaseOrder.WorkflowStatus poStatus = po.getWorkflowStatus();
    if (poStatus != PENDING) {
      throw new HttpException(422, poStatus == OPEN ? ErrorCodes.ORDER_OPEN : ErrorCodes.ORDER_CLOSED);
    }
    return po;
  }

  /**
   * Creates PO Line assuming its content is valid and all restriction checks have been already passed
   * @param compPoLine {@link CompositePoLine} to be created
   * @param compOrder associated {@link CompositePurchaseOrder} object
   * @return completable future which might hold {@link CompositePoLine} on success or an exception if any issue happens
   */
  CompletableFuture<CompositePoLine> createPoLine(CompositePoLine compPoLine, CompositePurchaseOrder compOrder) {
    // The id is required because sub-objects are being created first
    if (isEmpty(compPoLine.getId())) {
      compPoLine.setId(UUID.randomUUID().toString());
    }
    compPoLine.setPurchaseOrderId(compOrder.getId());
    updateEstimatedPrice(compPoLine);
    updateLocationsQuantity(compPoLine.getLocations());

    JsonObject line = mapFrom(compPoLine);
    List<CompletableFuture<Void>> subObjFuts = new ArrayList<>();

    subObjFuts.add(createAlerts(compPoLine, line));
    subObjFuts.add(createReportingCodes(compPoLine, line));

    return allOf(subObjFuts.toArray(new CompletableFuture[0]))
      .thenCompose(v -> generateLineNumber(compOrder))
      .thenAccept(lineNumber -> line.put(PO_LINE_NUMBER, lineNumber))
      .thenCompose(v -> createPoLineSummary(compPoLine, line));
  }

  CompletableFuture<Void> setTenantDefaultCreateInventoryValues(CompositePoLine compPOL) {
    CompletableFuture<JsonObject> future = new VertxCompletableFuture<>(ctx);

    if (isCreateInventoryNull(compPOL)) {
      getTenantConfiguration()
        .thenApply(config -> {
          if (StringUtils.isNotEmpty(config.getString(CREATE_INVENTORY))) {
            return future.complete(new JsonObject(config.getString(CREATE_INVENTORY)));
          } else {
            return future.complete(new JsonObject());
          }
        })
        .exceptionally(t -> future.complete(new JsonObject()));
      return future
        .thenAccept(jsonConfig -> updateCreateInventory(compPOL, jsonConfig));
    } else {
      return completedFuture(null);
    }
  }

  public static boolean isCreateInventoryNull(CompositePoLine compPOL) {
    switch (compPOL.getOrderFormat()) {
      case P_E_MIX:
        return isEresourceInventoryNotPresent(compPOL)
          || isPhysicalInventoryNotPresent(compPOL);
      case ELECTRONIC_RESOURCE:
        return isEresourceInventoryNotPresent(compPOL);
      case OTHER:
      case PHYSICAL_RESOURCE:
        return isPhysicalInventoryNotPresent(compPOL);
      default:
        return false;
    }
  }

  private static Boolean isPhysicalInventoryNotPresent(CompositePoLine compPOL) {
    return Optional.ofNullable(compPOL.getPhysical())
      .map(physical -> physical.getCreateInventory() == null)
      .orElse(true);
  }

  private static Boolean isEresourceInventoryNotPresent(CompositePoLine compPOL) {
    return Optional.ofNullable(compPOL.getEresource())
      .map(eresource -> eresource.getCreateInventory() == null)
      .orElse(true);
  }

  /**
   * get the tenant configuration for the orderFormat, if not present assign the defaults
   * Default values:
   * Physical : CreateInventory.INSTANCE_HOLDING_ITEM
   * Eresource: CreateInventory.INSTANCE_HOLDING
   *
   * @param compPOL
   * @param jsonConfig
   */
  private void updateCreateInventory(CompositePoLine compPOL, JsonObject jsonConfig) {
    // try to set createInventory by values from mod-configuration. If empty -
    // set default hardcoded values
    if (compPOL.getOrderFormat().equals(OrderFormat.ELECTRONIC_RESOURCE)
      || compPOL.getOrderFormat().equals(OrderFormat.P_E_MIX)) {
      String tenantDefault = jsonConfig.getString(ERESOURCE);
      Eresource.CreateInventory eresourceDefaultValue = getEresourceInventoryDefault(tenantDefault);
      if (compPOL.getEresource() == null) {
        compPOL.setEresource(new Eresource());
      }
      if(isEresourceInventoryNotPresent(compPOL)) {
        compPOL.getEresource().setCreateInventory(eresourceDefaultValue);
      }
    }
    if (!compPOL.getOrderFormat().equals(OrderFormat.ELECTRONIC_RESOURCE)) {
      String tenantDefault = compPOL.getOrderFormat().equals(OrderFormat.OTHER) ? jsonConfig.getString(OTHER)
        : jsonConfig.getString(PHYSICAL);
      Physical.CreateInventory createInventoryDefaultValue = getPhysicalInventoryDefault(tenantDefault);
      if (compPOL.getPhysical() == null) {
        compPOL.setPhysical(new Physical());
      }
      if (isPhysicalInventoryNotPresent(compPOL)) {
        compPOL.getPhysical().setCreateInventory(createInventoryDefaultValue);
      }
    }
  }

  private Physical.CreateInventory getPhysicalInventoryDefault(String tenantDefault) {
    return StringUtils.isEmpty(tenantDefault)
      ? Physical.CreateInventory.INSTANCE_HOLDING_ITEM
      : Physical.CreateInventory.fromValue(tenantDefault);
  }

  private Eresource.CreateInventory getEresourceInventoryDefault(String tenantDefault) {
    return StringUtils.isEmpty(tenantDefault)
      ? Eresource.CreateInventory.INSTANCE_HOLDING
      : Eresource.CreateInventory.fromValue(tenantDefault);
  }

  CompletableFuture<CompositePoLine> getCompositePoLine(String polineId) {
    return getPoLineById(polineId, lang, httpClient, ctx, okapiHeaders, logger)
      .thenCompose(line -> getCompositePurchaseOrder(line.getString(PURCHASE_ORDER_ID))
        .thenCompose(order -> protectionHelper.isOperationRestricted(order.getAcqUnitIds(), ProtectedOperationType.READ))
        .thenCompose(ok -> populateCompositeLine(line)));
  }

  CompletableFuture<Void> deleteLine(String lineId) {
    return getPoLineById(lineId, lang, httpClient, ctx, okapiHeaders, logger)
      .thenCompose(this::verifyDeleteAllowed)
      .thenCompose(line -> {
        logger.debug("Deleting PO line...");
        return deletePoLine(line, httpClient, ctx, okapiHeaders, logger)
          .thenCompose(v -> financeHelper.releasePoLineEncumbrances(lineId));
      })
      .thenAccept(json -> logger.info("The PO Line with id='{}' has been deleted successfully", lineId));
  }

  private CompletableFuture<JsonObject> verifyDeleteAllowed(JsonObject line) {
    return getCompositePurchaseOrder(line.getString(PURCHASE_ORDER_ID))
      .thenCompose(order -> protectionHelper.isOperationRestricted(order.getAcqUnitIds(), DELETE))
      .thenApply(aVoid -> line);
  }

  /**
   * Handles update of the order line. First retrieve the PO line from storage and depending on its content handle passed PO line.
   */
  CompletableFuture<Void> updateOrderLine(CompositePoLine compOrderLine) {
    return getPoLineByIdAndValidate(compOrderLine.getPurchaseOrderId(), compOrderLine.getId())
        .thenCompose(lineFromStorage -> getCompositePurchaseOrder(compOrderLine.getPurchaseOrderId())
          .thenCompose(compOrder -> {
            validatePOLineProtectedFieldsChanged(compOrderLine, lineFromStorage, compOrder);
            updateLocationsQuantity(compOrderLine.getLocations());
            updateEstimatedPrice(compOrderLine);

            return checkLocationsAndPiecesConsistency(compOrderLine, compOrder)
              .thenCompose(vVoid -> protectionHelper.isOperationRestricted(compOrder.getAcqUnitIds(), UPDATE)
                .thenCompose(v -> validateAndNormalizeISBN(compOrderLine))
                .thenCompose(v -> validateAccessProviders(compOrderLine))
                .thenCompose(v -> processOpenedPoLine(compOrder, compOrderLine))
                .thenApply(v -> lineFromStorage));
          }))
        .thenCompose(lineFromStorage -> {
          // override PO line number in the request with one from the storage, because it's not allowed to change it during PO line
          // update
          compOrderLine.setPoLineNumber(lineFromStorage.getString(PO_LINE_NUMBER));
          return updateOrderLine(compOrderLine, lineFromStorage)
            .thenAccept(ok -> updateOrderStatus(compOrderLine, lineFromStorage));
        });

  }

  private CompletableFuture<Void> processOpenedPoLine(CompositePurchaseOrder compOrder, CompositePoLine compositePoLine) {
    CompletableFuture<Void> future = new VertxCompletableFuture<>(ctx);

    if (compOrder.getWorkflowStatus() == OPEN) {
      return processEncumbrance(compOrder, compositePoLine)
        .thenApply(holder -> null);
    } else {
      future.complete(null);
    }
    return future;
  }

  public CompletableFuture<EncumbrancesProcessingHolder> processEncumbrance(CompositePurchaseOrder compPO, CompositePoLine compositePoLine) {
    EncumbrancesProcessingHolder holder = new EncumbrancesProcessingHolder();
    List<CompositePoLine> compositePoLines = Collections.singletonList(compositePoLine);

    if (!compositePoLine.getFundDistribution().isEmpty()) {
      return CompletableFuture.runAsync(() -> validateFundDistributionTotal(compositePoLines))
        .thenCompose(v -> financeHelper.getPoLineEncumbrances(compositePoLine.getId()))
        .thenAccept(holder::withEncumbrancesFromStorage)
        .thenCompose(v -> financeHelper.buildNewEncumbrances(compPO, compositePoLines, holder.getEncumbrancesFromStorage()))
        .thenAccept(holder::withEncumbrancesForCreate)
        .thenCompose(v -> financeHelper.buildEncumbrancesForUpdate(compositePoLines, holder.getEncumbrancesFromStorage()))
        .thenAccept(holder::withEncumbrancesForUpdate)
        .thenApply(v -> financeHelper.findNeedReleaseEncumbrances(compositePoLines, holder.getEncumbrancesFromStorage()))
        .thenAccept(holder::withEncumbrancesForRelease)
        .thenCompose(v -> createOrUpdateOrderTransactionSummary(compPO.getId(), holder))
        .thenCompose(v -> createOrUpdateEncumbrances(holder))
        .thenApply(v -> holder);
    }
    return CompletableFuture.completedFuture(null);
  }


  CompletableFuture<Void> createOrUpdateOrderTransactionSummary(String orderId, EncumbrancesProcessingHolder holder) {
    if (CollectionUtils.isEmpty(holder.getEncumbrancesFromStorage())) {
      return financeHelper.createOrderTransactionSummary(orderId, holder.getAllEncumbrancesQuantity())
        .thenApply(id -> null);
    }
    else {
      return financeHelper.updateOrderTransactionSummary(orderId, holder.getAllEncumbrancesQuantity());
    }
  }

  CompletableFuture<Void> createOrUpdateEncumbrances(EncumbrancesProcessingHolder holder) {
    return createEncumbrancesAndUpdatePoLines(holder.getEncumbrancesForCreate())
      .thenCompose(v -> financeHelper.releaseEncumbrances(holder.getEncumbrancesForRelease()))
      .thenCompose(v -> updateEncumbrances(holder));
  }

  private CompletionStage<Void> updateEncumbrances(EncumbrancesProcessingHolder holder) {
    List<Transaction> encumbrances = holder.getEncumbrancesForUpdate().stream()
      .map(EncumbranceRelationsHolder::getTransaction)
      .collect(toList());
    return financeHelper.updateTransactions(encumbrances);
  }

  public CompletableFuture<Void> createEncumbrancesAndUpdatePoLines(List<EncumbranceRelationsHolder> relationsHolders) {
    return VertxCompletableFuture.allOf(ctx, relationsHolders.stream()
      .map(holder -> createRecordInStorage(JsonObject.mapFrom(holder.getTransaction()), String.format(ENCUMBRANCE_POST_ENDPOINT, lang))
        .thenCompose(id -> {
          PoLineFundHolder poLineFundHolder = holder.getPoLineFundHolder();
          poLineFundHolder.getFundDistribution().setEncumbrance(id);
          return updatePoLinesSummary(Collections.singletonList(poLineFundHolder.getPoLine()));
        })
        .exceptionally(fail -> {
          checkCustomTransactionError(fail);
          throw new CompletionException(fail);
        })
      )
      .toArray(CompletableFuture[]::new)
    );
  }


  void validateFundDistributionTotal(List<CompositePoLine> compositePoLines) {
    for (CompositePoLine cPoLine : compositePoLines) {

      if (cPoLine.getCost().getPoLineEstimatedPrice() != null && !cPoLine.getFundDistribution().isEmpty()) {
        Double poLineEstimatedPrice = cPoLine.getCost().getPoLineEstimatedPrice();
        String currency = cPoLine.getCost().getCurrency();
        MonetaryAmount remainingAmount = Money.of(poLineEstimatedPrice, currency);

        for (FundDistribution fundDistribution : cPoLine.getFundDistribution()) {
          FundDistribution.DistributionType dType = fundDistribution.getDistributionType();
          Double value = fundDistribution.getValue();
          MonetaryAmount amountValueMoney = Money.of(value, currency);

          if (dType == FundDistribution.DistributionType.PERCENTAGE) {
            /**
             * calculate remaining amount to carry forward, required if there are more fund distributions with percentage and
             * percentToAmount = poLineEstimatedPrice * value/100;
             */
            MonetaryAmount poLineEstimatedPriceMoney = Money.of(poLineEstimatedPrice, currency);
            // convert percent to amount
            MonetaryAmount percentToAmount = poLineEstimatedPriceMoney.with(MonetaryOperators.percent(value));
            amountValueMoney = percentToAmount;
          }

          remainingAmount = remainingAmount.subtract(amountValueMoney);
        }
        if (!remainingAmount.isZero()) {
          throw new HttpException(422, INCORRECT_FUND_DISTRIBUTION_TOTAL);
        }
      }
    }
  }

  private CompletableFuture<Void> validateAccessProviders(CompositePoLine compOrderLine) {
    return new VendorHelper(httpClient, okapiHeaders, ctx, lang)
      .validateAccessProviders(Collections.singletonList(compOrderLine))
      .thenAccept(errors -> {
        if (!errors.getErrors().isEmpty()) {
          throw new HttpException(422, errors.getErrors().get(0));
        }
      });
  }


  private void validatePOLineProtectedFieldsChanged(CompositePoLine compOrderLine, JsonObject lineFromStorage, CompositePurchaseOrder purchaseOrder) {
    if (purchaseOrder.getWorkflowStatus() != PENDING) {
      verifyProtectedFieldsChanged(POLineProtectedFields.getFieldNames(), lineFromStorage, JsonObject.mapFrom(compOrderLine));
    }
  }

  private void updateOrderStatus(CompositePoLine compOrderLine, JsonObject lineFromStorage) {
    supplyBlockingAsync(ctx, () -> lineFromStorage.mapTo(PoLine.class))
      .thenAccept(poLine -> {
        // See MODORDERS-218
        if (!StringUtils.equals(poLine.getReceiptStatus().value(), compOrderLine.getReceiptStatus().value())
          || !StringUtils.equals(poLine.getPaymentStatus().value(), compOrderLine.getPaymentStatus().value())) {
          sendEvent(MessageAddress.RECEIVE_ORDER_STATUS_UPDATE, createUpdateOrderMessage(compOrderLine));
        }
      });
  }

  private JsonObject createUpdateOrderMessage(CompositePoLine compOrderLine) {
    return new JsonObject().put(EVENT_PAYLOAD, new JsonArray().add(new JsonObject().put(ORDER_ID, compOrderLine.getPurchaseOrderId())));
  }

  /**
   * Handles update of the order line depending on the content in the storage. Returns {@link CompletableFuture} as a result.
   * In case the exception happened in future lifecycle, the caller should handle it. The logic is like following:<br/>
   * 1. Handle sub-objects operations's. All the exception happened for any sub-object are handled generating an error.
   * All errors can be retrieved by calling {@link #getErrors()}.<br/>
   * 2. Store PO line summary. On success, the logic checks if there are no errors happened on sub-objects operations and
   * returns succeeded future. Otherwise {@link HttpException} will be returned as result of the future.
   *
   * @param compOrderLine The composite {@link CompositePoLine} to use for storage data update
   * @param lineFromStorage {@link JsonObject} representing PO line from storage (/acq-models/mod-orders-storage/schemas/po_line.json)
   */
  CompletableFuture<Void> updateOrderLine(CompositePoLine compOrderLine, JsonObject lineFromStorage) {
    CompletableFuture<Void> future = new VertxCompletableFuture<>(ctx);

    updatePoLineSubObjects(compOrderLine, lineFromStorage)
      .thenCompose(poLine -> updateOrderLineSummary(compOrderLine.getId(), poLine))
      .thenAccept(json -> {
        if (getErrors().isEmpty()) {
          future.complete(null);
        } else {
          String message = String.format("PO Line with '%s' id partially updated but there are issues processing some PO Line sub-objects",
            compOrderLine.getId());
          future.completeExceptionally(new HttpException(500, message));
        }
      })
      .exceptionally(throwable -> {
        future.completeExceptionally(throwable);
        return null;
      });

    return future;
  }

  /**
   * Handle update of the order line without sub-objects
   */
  public CompletableFuture<JsonObject> updateOrderLineSummary(String poLineId, JsonObject poLine) {
    logger.debug("Updating PO line...");
    String endpoint = String.format(URL_WITH_LANG_PARAM, resourceByIdPath(PO_LINES, poLineId), lang);
    return operateOnObject(HttpMethod.PUT, endpoint, poLine, httpClient, ctx, okapiHeaders, logger);
  }

  public CompletableFuture<Void> updatePoLinesSummary(List<CompositePoLine> compositePoLines) {
    return VertxCompletableFuture.allOf(ctx, compositePoLines.stream()
      .map(HelperUtils::convertToPoLine)
      .map(line -> updateOrderLineSummary(line.getId(), JsonObject.mapFrom(line)))
      .toArray(CompletableFuture[]::new));
  }

  /**
   * Creates Inventory records associated with given PO line and updates PO line with corresponding links.
   *
   * @param compPOL Composite PO line to update Inventory for
   * @return CompletableFuture with void.
   */
  CompletableFuture<Void> updateInventory(CompositePoLine compPOL, String titleId) {
    if (Boolean.TRUE.equals(compPOL.getIsPackage())) {
      return completedFuture(null);
    }
    if (inventoryUpdateNotRequired(compPOL)) {
      // don't create pieces, if no inventory updates and receiving not required
      if (isReceiptNotRequired(compPOL.getReceiptStatus())) {
        return completedFuture(null);
      }
      return createPieces(compPOL, titleId, Collections.emptyList()).thenRun(
          () -> logger.info("Create pieces for PO Line with '{}' id where inventory updates are not required", compPOL.getId()));
    }

    return inventoryHelper.handleInstanceRecord(compPOL)
      .thenCompose(inventoryHelper::handleHoldingsAndItemsRecords)
      .thenCompose(piecesWithItemId -> {
        if (isReceiptNotRequired(compPOL.getReceiptStatus())) {
          return completedFuture(null);
        }
        //create pieces only if receiving is required
        return createPieces(compPOL, titleId, piecesWithItemId);
      });
  }

  private boolean isReceiptNotRequired(ReceiptStatus receiptStatus) {
    return receiptStatus == CompositePoLine.ReceiptStatus.RECEIPT_NOT_REQUIRED;
  }

  String buildNewPoLineNumber(PoLine poLineFromStorage, String poNumber) {
    String oldPoLineNumber = poLineFromStorage.getPoLineNumber();
    Matcher matcher = PO_LINE_NUMBER_PATTERN.matcher(oldPoLineNumber);
    if (matcher.find()) {
      return buildPoLineNumber(poNumber, matcher.group(2));
    }
    logger.error("PO Line - {} has invalid or missing number.", poLineFromStorage.getId());
    return oldPoLineNumber;
  }

  void sortPoLinesByPoLineNumber(List<CompositePoLine> poLines) {
    poLines.sort(this::comparePoLinesByPoLineNumber);
  }

  /**
   * Validates purchase order line content. If content is okay, checks if allowed PO Lines limit is not exceeded.
   * @param compPOL Purchase Order Line to validate
   * @return completable future which might be completed with {@code true} if line is valid, {@code false} if not valid or an exception if processing fails
   */
  private CompletableFuture<Boolean> validateNewPoLine(CompositePoLine compPOL) {
    logger.debug("Validating if PO Line is valid...");

    // PO id is required for PO Line to be created
    if (compPOL.getPurchaseOrderId() == null) {
      addProcessingError(ErrorCodes.MISSING_ORDER_ID_IN_POL.toError());
    }
    addProcessingErrors(validatePoLine(compPOL));

    // If static validation has failed, no need to call other services
    if (!getErrors().isEmpty()) {
      return completedFuture(false);
    }

    return allOf(validatePoLineLimit(compPOL),validateAndNormalizeISBN(compPOL))
      .thenApply(v -> getErrors().isEmpty());
  }

  private CompletableFuture<Boolean> validatePoLineLimit(CompositePoLine compPOL) {
    String query = PURCHASE_ORDER_ID + "==" + compPOL.getPurchaseOrderId();
    return getTenantConfiguration()
      .thenCombine(getPoLines(0, 0, query), (config, poLines) -> {
        boolean isValid = poLines.getTotalRecords() < getPoLineLimit(config);
        if (!isValid) {
          addProcessingError(ErrorCodes.POL_LINES_LIMIT_EXCEEDED.toError());
        }
        return isValid;
      });
  }

  private CompletableFuture<CompositePurchaseOrder> getCompositePurchaseOrder(String purchaseOrderId) {
    return getPurchaseOrderById(purchaseOrderId, lang, httpClient, ctx, okapiHeaders, logger)
      .thenApply(HelperUtils::convertToCompositePurchaseOrder)
      .exceptionally(t -> {
        Throwable cause = t.getCause();
        // The case when specified order does not exist
        if (cause instanceof HttpException && ((HttpException) cause).getCode() == Response.Status.NOT_FOUND.getStatusCode()) {
          throw new HttpException(422, ErrorCodes.ORDER_NOT_FOUND);
        }
        throw t instanceof CompletionException ? (CompletionException) t : new CompletionException(cause);
      });
  }

  private CompletableFuture<String> generateLineNumber(CompositePurchaseOrder compOrder) {
    return handleGetRequest(getPoLineNumberEndpoint(compOrder.getId()), httpClient, ctx, okapiHeaders, logger)
      .thenApply(sequenceNumberJson -> {
        SequenceNumber sequenceNumber = sequenceNumberJson.mapTo(SequenceNumber.class);
        return buildPoLineNumber(compOrder.getPoNumber(), sequenceNumber.getSequenceNumber());
      });
  }


  private CompletionStage<CompositePoLine> populateCompositeLine(JsonObject poline) {
    return HelperUtils.operateOnPoLine(HttpMethod.GET, poline, httpClient, ctx, okapiHeaders, logger)
      .thenCompose(this::getLineWithInstanceId);
  }

  private CompletableFuture<Title> getTitleForPoLine(CompositePoLine line) {
    return new TitlesHelper(httpClient, okapiHeaders, ctx, lang)
      .getTitles(1, 0, QUERY_BY_PO_LINE_ID + line.getId())
      .thenCompose(titleCollection -> {
        List<Title> titles = titleCollection.getTitles();
        if (!titles.isEmpty()) {
          return CompletableFuture.completedFuture(titles.get(0));
        }
        throw new HttpException(422, ErrorCodes.TITLE_NOT_FOUND);
      });
  }

  private CompletableFuture<CompositePoLine> getLineWithInstanceId(CompositePoLine line) {
     if (!Boolean.TRUE.equals(line.getIsPackage())) {
       return new TitlesHelper(httpClient, okapiHeaders, ctx, lang)
         .getTitles(1, 0, QUERY_BY_PO_LINE_ID + line.getId())
         .thenApply(titleCollection -> {
           List<Title> titles = titleCollection.getTitles();
           if (!titles.isEmpty()) {
             line.setInstanceId(titles.get(0).getInstanceId());
           }
           return line;
         });
    } else {
       return CompletableFuture.completedFuture(line);
     }
  }

  private String buildPoLineNumber(String poNumber, String sequence) {
    return poNumber + DASH_SEPARATOR + sequence;
  }

  /**
   * See MODORDERS-180 for more details.
   * @param compPoLine composite PO Line
   */
  public void updateEstimatedPrice(CompositePoLine compPoLine) {
    Cost cost = compPoLine.getCost();
    cost.setPoLineEstimatedPrice(calculateEstimatedPrice(cost).getNumber().doubleValue());
  }

  public void updateLocationsQuantity(List<Location> locations) {
    locations.forEach(location -> location.setQuantity(calculateTotalLocationQuantity(location)));
  }

  /**
   * Creates pieces that are not yet in storage
   *
   * @param compPOL PO line to create Pieces Records for
   * @param expectedPiecesWithItem expected Pieces to create with created associated Items records
   * @return void future
   */
  private CompletableFuture<Void> createPieces(CompositePoLine compPOL, String titleId, List<Piece> expectedPiecesWithItem) {
    int createdItemsQuantity = expectedPiecesWithItem.size();
    // do not create pieces in case of check-in flow
    if (compPOL.getCheckinItems() != null && compPOL.getCheckinItems()) {
      return completedFuture(null);
    }
    return searchForExistingPieces(compPOL)
      .thenCompose(existingPieces -> {
        List<Piece> piecesToCreate = new ArrayList<>();

        piecesToCreate.addAll(createPiecesByLocationId(compPOL, expectedPiecesWithItem, existingPieces));
        piecesToCreate.addAll(createPiecesWithoutLocationId(compPOL, existingPieces));
        piecesToCreate.forEach(piece -> piece.setTitleId(titleId));

        return allOf(piecesToCreate.stream().map(this::createPiece).toArray(CompletableFuture[]::new));
      })
      .thenAccept(v -> validateItemsCreation(compPOL, createdItemsQuantity));
  }

  private List<Piece> createPiecesWithoutLocationId(CompositePoLine compPOL, List<Piece> existingPieces) {
    List<Piece> piecesToCreate = new ArrayList<>();
    Map<Piece.PieceFormat, Integer> expectedQuantitiesWithoutLocation = calculatePiecesQuantityWithoutLocation(compPOL);
    Map<Piece.PieceFormat, Integer> existingPiecesQuantities = calculateQuantityOfExistingPiecesWithoutLocation(existingPieces);
    expectedQuantitiesWithoutLocation.forEach((format, expectedQty) -> {
      int remainingPiecesQuantity = expectedQty - existingPiecesQuantities.getOrDefault(format, 0);
      if (remainingPiecesQuantity > 0) {
        for (int i = 0; i < remainingPiecesQuantity; i++) {
          piecesToCreate.add(new Piece().withFormat(format).withPoLineId(compPOL.getId()));
        }
      }
    });
    return piecesToCreate;
  }

  private List<Piece> createPiecesByLocationId(CompositePoLine compPOL, List<Piece> expectedPiecesWithItem, List<Piece> existingPieces) {
    List<Piece> piecesToCreate = new ArrayList<>();
    // For each location collect pieces that need to be created.
    groupLocationsById(compPOL)
      .forEach((locationId, locations) -> {
        List<Piece> filteredExistingPieces = filterByLocationId(existingPieces, locationId);
        List<Piece> filteredExpectedPiecesWithItem = filterByLocationId(expectedPiecesWithItem, locationId);
        piecesToCreate.addAll(collectMissingPiecesWithItem(filteredExpectedPiecesWithItem, filteredExistingPieces));

        Map<Piece.PieceFormat, Integer> expectedQuantitiesWithoutItem = HelperUtils.calculatePiecesWithoutItemIdQuantity(compPOL, locations);
        Map<Piece.PieceFormat, Integer> quantityWithoutItem = calculateQuantityOfExistingPiecesWithoutItem(filteredExistingPieces);
        expectedQuantitiesWithoutItem.forEach((format, expectedQty) -> {
          int remainingPiecesQuantity = expectedQty - quantityWithoutItem.getOrDefault(format, 0);
          if (remainingPiecesQuantity > 0) {
            for (int i = 0; i < remainingPiecesQuantity; i++) {
              piecesToCreate.add(new Piece().withFormat(format).withLocationId(locationId).withPoLineId(compPOL.getId()));
            }
          }
        });
      });
    return piecesToCreate;
  }


  /**
   * Search for pieces which might be already created for the PO line
   * @param compPOL PO line to retrieve Piece Records for
   * @return future with list of Pieces
   */
  private CompletableFuture<List<Piece>> searchForExistingPieces(CompositePoLine compPOL) {
    String endpoint = String.format(LOOKUP_PIECES_ENDPOINT, compPOL.getId(), calculateTotalQuantity(compPOL), lang);
    return handleGetRequest(endpoint, httpClient, ctx, okapiHeaders, logger)
      .thenApply(body -> {
        PieceCollection existedPieces = body.mapTo(PieceCollection.class);
        logger.debug("{} existing pieces found out for PO Line with '{}' id", existedPieces.getTotalRecords(), compPOL.getId());
        return existedPieces.getPieces();
      });
  }

  private List<Piece> filterByLocationId(List<Piece> pieces, String locationId) {
    return pieces.stream()
      .filter(piece -> locationId.equals(piece.getLocationId()))
      .collect(Collectors.toList());
  }

  /**
   * Find pieces for which created items, but which are not yet in the storage.
   *
   * @param piecesWithItem pieces for which created items
   * @param existingPieces pieces from storage
   * @return List of Pieces with itemId that are not in storage.
   */
  private List<Piece> collectMissingPiecesWithItem(List<Piece> piecesWithItem, List<Piece> existingPieces) {
    return piecesWithItem.stream()
      .filter(pieceWithItem -> existingPieces.stream()
        .noneMatch(existingPiece -> pieceWithItem.getItemId().equals(existingPiece.getItemId())))
      .collect(Collectors.toList());
  }

  private Map<Piece.PieceFormat, Integer> calculateQuantityOfExistingPiecesWithoutItem(List<Piece> pieces) {
    return StreamEx.of(pieces)
      .filter(piece -> StringUtils.isEmpty(piece.getItemId()))
      .groupingBy(Piece::getFormat, collectingAndThen(toList(), List::size));
  }

  private Map<Piece.PieceFormat, Integer> calculateQuantityOfExistingPiecesWithoutLocation(List<Piece> pieces) {
    return StreamEx.of(pieces)
      .filter(piece -> StringUtils.isEmpty(piece.getLocationId()))
      .groupingBy(Piece::getFormat, collectingAndThen(toList(), List::size));
  }

  private void validateItemsCreation(CompositePoLine compPOL, int itemsSize) {
    int expectedItemsQuantity = calculateInventoryItemsQuantity(compPOL);
    if (itemsSize != expectedItemsQuantity) {
      String message = String.format("Error creating items for PO Line with '%s' id. Expected %d but %d created",
        compPOL.getId(), expectedItemsQuantity, itemsSize);
      throw new InventoryException(message);
    }
  }

  /**
   * Create Piece associated with PO Line in the storage
   *
   * @param piece associated with PO Line
   * @return CompletableFuture
   */
  private CompletableFuture<Void> createPiece(Piece piece) {
    CompletableFuture<Void> future = new VertxCompletableFuture<>(ctx);

    JsonObject pieceObj = mapFrom(piece);
    createRecordInStorage(pieceObj, resourcesPath(PIECES))
      .thenAccept(id -> future.complete(null))
      .exceptionally(t -> {
        logger.error("The piece record failed to be created. The request body: {}", pieceObj.encodePrettily());
        future.completeExceptionally(t);
        return null;
      });

    return future;
  }

  private CompletionStage<JsonObject> updatePoLineSubObjects(CompositePoLine compOrderLine, JsonObject lineFromStorage) {
    JsonObject updatedLineJson = mapFrom(compOrderLine);
    logger.debug("Updating PO line sub-objects...");

    List<CompletableFuture<Void>> futures = new ArrayList<>();

    futures.add(handleSubObjsOperation(ALERTS, updatedLineJson, lineFromStorage));
    futures.add(handleSubObjsOperation(REPORTING_CODES, updatedLineJson, lineFromStorage));

    // Once all operations completed, return updated PO Line with new sub-object id's as json object
    return allOf(futures.toArray(new CompletableFuture[0]))
      .thenApply(v -> updatedLineJson);
  }

  private CompletableFuture<String> handleSubObjOperation(String prop, JsonObject subObjContent, String storageId) {
    final String url;
    final HttpMethod operation;
    // In case the id is available in the PO line from storage, depending on the request content the sub-object is going to be updated or removed
    if (StringUtils.isNotEmpty(storageId)) {
      url = String.format(URL_WITH_LANG_PARAM, resourceByIdPath(prop, storageId), lang);
      operation = (subObjContent != null) ? HttpMethod.PUT : HttpMethod.DELETE;
    } else if (subObjContent != null) {
      operation = HttpMethod.POST;
      url = String.format(URL_WITH_LANG_PARAM, resourcesPath(prop), lang);
    } else {
      // There is no object in storage nor in request - skipping operation
      return completedFuture(null);
    }

    return operateOnObject(operation, url, subObjContent, httpClient, ctx, okapiHeaders, logger)
      .thenApply(json -> {
        if (operation == HttpMethod.PUT) {
          return storageId;
        } else if (operation == HttpMethod.POST && json.getString(ID) != null) {
          return json.getString(ID);
        }
        return null;
      });
  }

  private CompletableFuture<Void> handleSubObjsOperation(String prop, JsonObject updatedLine, JsonObject lineFromStorage) {
    List<CompletableFuture<String>> futures = new ArrayList<>();
    JsonArray idsInStorage = lineFromStorage.getJsonArray(prop);
    JsonArray jsonObjects = updatedLine.getJsonArray(prop);

    // Handle updated sub-objects content
    if (jsonObjects != null && !jsonObjects.isEmpty()) {
      // Clear array of object which will be replaced with array of id's
      updatedLine.remove(prop);
      for (int i = 0; i < jsonObjects.size(); i++) {
        JsonObject subObj = jsonObjects.getJsonObject(i);
        if (subObj != null  && subObj.getString(ID) != null) {
          String id = idsInStorage.remove(subObj.getString(ID)) ? subObj.getString(ID) : null;

          futures.add(handleSubObjOperation(prop, subObj, id)
            .exceptionally(throwable -> {
              handleProcessingError(throwable, prop, id);
              return null;
            })
          );
        }
      }
    }

    // The remaining unprocessed objects should be removed
    for (int i = 0; i < idsInStorage.size(); i++) {
      String id = idsInStorage.getString(i);
      if (id != null) {
        futures.add(handleSubObjOperation(prop, null, id)
          .exceptionally(throwable -> {
            handleProcessingError(throwable, prop, id);
            // In case the object is not deleted, still keep reference to old id
            return id;
          })
        );
      }
    }

    return collectResultsOnSuccess(futures)
      .thenAccept(newIds -> updatedLine.put(prop, newIds));
  }

  private void handleProcessingError(Throwable exc, String propName, String propId) {
    Error error = new Error().withMessage(exc.getMessage());
    error.getParameters()
      .add(new Parameter().withKey(propName)
        .withValue(propId));

    addProcessingError(error);
  }

  /**
   * Retrieves PO line from storage by PO line id as JsonObject and validates order id match.
   */
  private CompletableFuture<JsonObject> getPoLineByIdAndValidate(String orderId, String lineId) {
    return getPoLineById(lineId, lang, httpClient, ctx, okapiHeaders, logger)
      .thenApply(line -> {
        logger.debug("Validating if the retrieved PO line corresponds to PO");
        validateOrderId(orderId, line);
        return line;
      });
  }

  /**
   * Validates if the retrieved PO line corresponds to PO (orderId). In case the PO line does not correspond to order id the exception is thrown
   * @param orderId order identifier
   * @param line PO line retrieved from storage
   */
  private void validateOrderId(String orderId, JsonObject line) {
    if (!StringUtils.equals(orderId, line.getString(PURCHASE_ORDER_ID))) {
      throw new HttpException(422, ErrorCodes.INCORRECT_ORDER_ID_IN_POL);
    }
  }

  private CompletionStage<CompositePoLine> createPoLineSummary(CompositePoLine compPOL, JsonObject line) {
    return createRecordInStorage(line, resourcesPath(PO_LINES))
      // On success set id and number of the created PO Line to composite object
      .thenApply(id -> compPOL.withId(id).withPoLineNumber(line.getString(PO_LINE_NUMBER)));
  }

  private String getPoLineNumberEndpoint(String id) {
    return PO_LINE_NUMBER_ENDPOINT + id;
  }

  private CompletableFuture<Void> createReportingCodes(CompositePoLine compPOL, JsonObject line) {
    List<CompletableFuture<String>> futures = new ArrayList<>();

    List<ReportingCode> reportingCodes = compPOL.getReportingCodes();
    if (null != reportingCodes)
      reportingCodes
        .forEach(reportingObject ->
          futures.add(createSubObjIfPresent(line, reportingObject, REPORTING_CODES, resourcesPath(REPORTING_CODES))
            .thenApply(id -> {
              if (id != null)
                reportingObject.setId(id);
              return id;
            }))
        );

    return collectResultsOnSuccess(futures)
      .thenAccept(reportingIds -> line.put(REPORTING_CODES, reportingIds))
      .exceptionally(t -> {
        logger.error("failed to create Reporting Codes", t);
        throw new CompletionException(t.getCause());
      });
  }

  private CompletableFuture<Void> createAlerts(CompositePoLine compPOL, JsonObject line) {
    List<CompletableFuture<String>> futures = new ArrayList<>();

    List<Alert> alerts = compPOL.getAlerts();
    if (null != alerts)
      alerts.forEach(alertObject ->
        futures.add(createSubObjIfPresent(line, alertObject, ALERTS, resourcesPath(ALERTS))
          .thenApply(id -> {
            if (id != null) {
              alertObject.setId(id);
            }
            return id;
          }))
      );

    return collectResultsOnSuccess(futures)
      .thenAccept(ids -> line.put(ALERTS, ids))
      .exceptionally(t -> {
        logger.error("failed to create Alerts", t);
        throw new CompletionException(t.getCause());
      });

  }

  private CompletableFuture<String> createSubObjIfPresent(JsonObject line, Object obj, String field, String url) {
    if (obj != null) {
      JsonObject json = mapFrom(obj);
      if (!json.isEmpty()) {
        return createRecordInStorage(json, url)
          .thenApply(id -> {
            logger.debug("The '{}' sub-object successfully created with id={}", field, id);
            line.put(field, id);
            return id;
          });
      }
    }
    return completedFuture(null);
  }

  private int comparePoLinesByPoLineNumber(CompositePoLine poLine1, CompositePoLine poLine2) {
    String poLineNumberSuffix1 = poLine1.getPoLineNumber().split(DASH_SEPARATOR)[1];
    String poLineNumberSuffix2 = poLine2.getPoLineNumber().split(DASH_SEPARATOR)[1];
    return Integer.parseInt(poLineNumberSuffix1) - Integer.parseInt(poLineNumberSuffix2);
  }

  public CompletableFuture<Void> validateAndNormalizeISBN(CompositePoLine compPOL) {
    if (HelperUtils.isProductIdsExist(compPOL)) {
      return inventoryHelper.getProductTypeUUID(ISBN)
        .thenCompose(id -> validateIsbnValues(compPOL, id)
          .thenAccept(aVoid -> removeISBNDuplicates(compPOL, id)));
    }
    return completedFuture(null);
  }

  CompletableFuture<Void> validateIsbnValues(CompositePoLine compPOL, String isbnTypeId) {
    CompletableFuture[] futures = compPOL.getDetails()
      .getProductIds()
      .stream()
      .filter(productId -> isISBN(isbnTypeId, productId))
      .map(productID -> inventoryHelper.convertToISBN13(productID.getProductId())
        .thenAccept(productID::setProductId))
      .toArray(CompletableFuture[]::new);

    return VertxCompletableFuture.allOf(ctx, futures);
  }

  private void removeISBNDuplicates(CompositePoLine compPOL, String isbnTypeId) {

    List<ProductId> notISBNs = getNonISBNProductIds(compPOL, isbnTypeId);

    List<ProductId> isbns = getDeduplicatedISBNs(compPOL, isbnTypeId);

    isbns.addAll(notISBNs);
    compPOL.getDetails().setProductIds(isbns);
  }

  private List<ProductId> getDeduplicatedISBNs(CompositePoLine compPOL, String isbnTypeId) {
    Map<String, List<ProductId>> uniqueISBNProductIds = compPOL.getDetails().getProductIds().stream()
      .filter(productId -> isISBN(isbnTypeId, productId))
      .distinct()
      .collect(groupingBy(ProductId::getProductId));

    return uniqueISBNProductIds.values().stream()
      .flatMap(productIds -> productIds.stream()
        .filter(isUniqueISBN(productIds)))
      .collect(toList());
  }

  private Predicate<ProductId> isUniqueISBN(List<ProductId> productIds) {
    return productId -> productIds.size() == 1 || StringUtils.isNotEmpty(productId.getQualifier());
  }

  private List<ProductId> getNonISBNProductIds(CompositePoLine compPOL, String isbnTypeId) {
    return compPOL.getDetails().getProductIds().stream()
      .filter(productId -> !isISBN(isbnTypeId, productId))
      .collect(toList());
  }

  private boolean isISBN(String isbnTypeId, ProductId productId) {
    return Objects.equals(productId.getProductIdType(), isbnTypeId);
  }

  private CompletableFuture<Void> checkLocationsAndPiecesConsistency(CompositePoLine poLine, CompositePurchaseOrder order) {
    if (isLocationsAndPiecesConsistencyNeedToBeVerified(poLine, order)) {
      String query = QUERY_BY_PO_LINE_ID + poLine.getId();
      return piecesHelper.getPieces(Integer.MAX_VALUE, 0, query)
        .thenCompose(pieces -> verifyLocationAndPieceConsistency(Collections.singletonList(poLine), pieces))
        .thenCompose(errorCode -> {
          if (PIECES_TO_BE_DELETED.equals(errorCode)) {
            throw new HttpException(422, PIECES_TO_BE_DELETED);
          } else if (PIECES_TO_BE_CREATED.equals(errorCode)){
            return getTitleForPoLine(poLine)
              .thenCompose(title -> updateInventory(poLine, title.getId()));
          }
          return CompletableFuture.completedFuture(null);
        });
    }
    return CompletableFuture.completedFuture(null);
  }

  private CompletableFuture<ErrorCodes> verifyLocationAndPieceConsistency(List<CompositePoLine> poLines, PieceCollection pieces) {
    if (CollectionUtils.isEmpty(poLines)) {
      return CompletableFuture.completedFuture(null);
    }

    VertxCompletableFuture<ErrorCodes> future = new VertxCompletableFuture<>(ctx);

    Map<String, Map<String, Integer>> numOfLocationsByPoLineIdAndLocationId = numOfLocationsByPoLineIdAndLocationId(poLines);
    Map<String, Map<String, Integer>> numOfPiecesByPoLineIdAndLocationId = numOfPiecesByPoLineAndLocationId(pieces);

    Set<String> pieceLocations = pieces.getPieces().stream().map(Piece::getLocationId).collect(toSet());
    Set<String> poLineLocations = poLines.stream().flatMap(poLine -> poLine.getLocations().stream()).map(Location::getLocationId)
      .collect(toSet());

    if (!pieceLocations.containsAll(poLineLocations)) {
      future.complete(PIECES_TO_BE_CREATED);
    } else {
      Set<ErrorCodes> resultErrorCodes = new HashSet<>();
      numOfPiecesByPoLineIdAndLocationId.forEach((poLineId, numOfPiecesByLocationId) -> numOfPiecesByLocationId
        .forEach((locationId, quantity) -> {

          Integer numOfPieces = Optional.ofNullable(numOfLocationsByPoLineIdAndLocationId)
            .map(map -> map.get(poLineId))
            .map(map -> map.get(locationId))
            .orElse(0);

          if (quantity > numOfPieces) {
            resultErrorCodes.add(PIECES_TO_BE_DELETED);
          } else if (quantity < numOfPieces) {
            resultErrorCodes.add(PIECES_TO_BE_CREATED);
          }
        }));

      if (resultErrorCodes.contains(PIECES_TO_BE_DELETED)) {
        future.complete(PIECES_TO_BE_DELETED);
      } else if (resultErrorCodes.contains(PIECES_TO_BE_CREATED)) {
        future.complete(PIECES_TO_BE_CREATED);
      } else {
        future.complete(null);
      }
    }
    return future;
  }

  private boolean isLocationsAndPiecesConsistencyNeedToBeVerified(CompositePoLine poLine, CompositePurchaseOrder order) {
    return order.getWorkflowStatus() == OPEN && Boolean.FALSE.equals(poLine.getCheckinItems())
      && poLine.getReceiptStatus() != ReceiptStatus.RECEIPT_NOT_REQUIRED && Boolean.FALSE.equals(poLine.getIsPackage());
  }
}
