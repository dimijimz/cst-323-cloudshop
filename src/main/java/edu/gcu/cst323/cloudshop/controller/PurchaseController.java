package edu.gcu.cst323.cloudshop.controller;

import edu.gcu.cst323.cloudshop.form.PurchaseForm;
import edu.gcu.cst323.cloudshop.model.Purchase;
import edu.gcu.cst323.cloudshop.service.ProductService;
import edu.gcu.cst323.cloudshop.service.PurchaseService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Buying a product and reviewing one's own purchases. Both routes are limited to
 * the BUYER role by {@code SecurityConfig}.
 *
 * <p>The buyer is always taken from the signed-in principal, never from the
 * request, so one buyer cannot purchase as, or read the history of, another.
 */
@Controller
public class PurchaseController {

    private static final Logger log = LoggerFactory.getLogger(PurchaseController.class);

    private final PurchaseService purchaseService;
    private final ProductService productService;

    /**
     * Creates the controller.
     *
     * @param purchaseService runs the purchase transaction and loads history
     * @param productService  reloads the product when the form has to be redisplayed
     */
    public PurchaseController(PurchaseService purchaseService, ProductService productService) {
        this.purchaseService = purchaseService;
        this.productService = productService;
    }

    /**
     * Buys the requested quantity of a product for the signed-in buyer.
     *
     * <p>On success the buyer is redirected to their purchase history with a
     * confirmation message. An invalid quantity redisplays the product page with
     * the field error. Insufficient stock is raised by the service and handled by
     * {@code GlobalExceptionHandler}, which returns the buyer to the product page
     * with an explanation.
     *
     * @param id                 the product id from the URL
     * @param form               the submitted quantity
     * @param binding            the validation result for the form
     * @param principal          the signed-in buyer
     * @param model              receives the product if the form is redisplayed
     * @param redirectAttributes carries the confirmation to the next page
     * @return a redirect to /my-purchases on success, otherwise the product detail view
     */
    @PostMapping("/products/{id}/purchase")
    public String purchase(@PathVariable Long id,
                           @Valid @ModelAttribute("purchaseForm") PurchaseForm form,
                           BindingResult binding,
                           Principal principal,
                           Model model,
                           RedirectAttributes redirectAttributes) {
        if (binding.hasErrors()) {
            log.warn("POST /products/{}/purchase - rejected with {} validation error(s)", id, binding.getErrorCount());
            model.addAttribute("product", productService.findActiveById(id));
            return "products/detail";
        }

        Purchase purchase = purchaseService.purchase(principal.getName(), id, form.getQuantity());

        redirectAttributes.addFlashAttribute("message", "Purchase confirmed: "
                + purchase.getQuantity() + " x " + purchase.getProduct().getName()
                + " for " + NumberFormat.getCurrencyInstance(Locale.US).format(purchase.getTotalPrice()) + ".");
        redirectAttributes.addFlashAttribute("confirmedPurchaseId", purchase.getId());
        return "redirect:/my-purchases";
    }

    /**
     * Lists the signed-in buyer's own purchases, newest first, with the total spent.
     *
     * @param principal the signed-in buyer
     * @param model     receives the purchases and their total
     * @return the purchase history view
     */
    @GetMapping("/my-purchases")
    public String myPurchases(Principal principal, Model model) {
        log.info("GET /my-purchases - listing purchases for {}", principal.getName());
        List<Purchase> purchases = purchaseService.findForBuyer(principal.getName());
        model.addAttribute("purchases", purchases);
        model.addAttribute("totalSpent", purchaseService.totalRevenue(purchases));
        return "purchases/history";
    }
}
