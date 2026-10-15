package edu.gcu.cst323.cloudshop.controller;

import edu.gcu.cst323.cloudshop.exception.ResourceNotFoundException;
import edu.gcu.cst323.cloudshop.form.PurchaseForm;
import edu.gcu.cst323.cloudshop.service.ProductService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The public catalog: the product list and each product's detail page.
 *
 * <p>Both pages are open to everyone, signed in or not, and show active products
 * only. A deactivated product answers 404 here even to someone holding its URL.
 *
 * @author Dimitri Jimenez
 */
@Controller
@RequestMapping("/products")
public class CatalogController {

    /** Supplies the active products and looks up a single one. */
    private final ProductService productService;

    /**
     * Creates the controller.
     *
     * @param productService supplies the active products
     */
    public CatalogController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * Lists every active product with its name, description, price and stock.
     * Products with no stock are listed but marked as out of stock.
     *
     * @param model receives the products
     * @return the catalog view
     */
    @GetMapping
    public String list(Model model) {
        model.addAttribute("products", productService.findCatalog());
        return "products/list";
    }

    /**
     * Shows one product, with a quantity form when it can be bought.
     *
     * @param id    the product id from the URL
     * @param model receives the product and an empty purchase form
     * @return the product detail view
     * @throws ResourceNotFoundException if the product does not exist or has been
     *                                   deactivated; {@code GlobalExceptionHandler}
     *                                   answers it with the 404 page
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("product", productService.findActiveById(id));
        model.addAttribute("purchaseForm", new PurchaseForm());
        return "products/detail";
    }
}
