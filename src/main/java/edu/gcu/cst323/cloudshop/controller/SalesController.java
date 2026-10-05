package edu.gcu.cst323.cloudshop.controller;

import edu.gcu.cst323.cloudshop.model.Purchase;
import edu.gcu.cst323.cloudshop.service.ProductService;
import edu.gcu.cst323.cloudshop.service.PurchaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

/**
 * The owner's sales report: every purchase in the shop, with optional filters
 * and a revenue total. Limited to the OWNER role by {@code SecurityConfig}.
 */
@Controller
@RequestMapping("/owner/sales")
public class SalesController {

    private static final Logger log = LoggerFactory.getLogger(SalesController.class);

    private final PurchaseService purchaseService;
    private final ProductService productService;

    /**
     * Creates the controller.
     *
     * @param purchaseService finds the purchases and totals the revenue
     * @param productService  supplies the products for the filter dropdown
     */
    public SalesController(PurchaseService purchaseService, ProductService productService) {
        this.purchaseService = purchaseService;
        this.productService = productService;
    }

    /**
     * Lists purchases with buyer, product, quantity, unit price, total and time,
     * narrowed by whichever filters are supplied, and totals the revenue of the
     * rows shown.
     *
     * <p>The filters arrive as query parameters, so a filtered report can be
     * bookmarked or shared as a link. Both dates are inclusive.
     *
     * @param productId only this product's sales, or null for every product
     * @param from      the first day to include, or null for no start date
     * @param to        the last day to include, or null for no end date
     * @param model     receives the sales, the totals and the current filter values
     * @return the sales view
     */
    @GetMapping
    public String sales(@RequestParam(required = false) Long productId,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        Model model) {
        log.info("GET /owner/sales - productId={} from={} to={}", productId, from, to);

        boolean invalidRange = from != null && to != null && from.isAfter(to);
        List<Purchase> sales = invalidRange ? List.of() : purchaseService.findSales(productId, from, to);

        model.addAttribute("sales", sales);
        model.addAttribute("revenueTotal", purchaseService.totalRevenue(sales));
        model.addAttribute("unitsSold", sales.stream().mapToInt(Purchase::getQuantity).sum());
        model.addAttribute("products", productService.findAll());
        model.addAttribute("productId", productId);
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("filtered", productId != null || from != null || to != null);
        if (invalidRange) {
            model.addAttribute("error", "The From date is after the To date, so no sales can match.");
        }
        return "owner/sales";
    }
}
