package edu.gcu.cst323.cloudshop.controller;

import edu.gcu.cst323.cloudshop.form.ProductForm;
import edu.gcu.cst323.cloudshop.model.Product;
import edu.gcu.cst323.cloudshop.service.ProductService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The owner's inventory pages: list, create, edit, deactivate and reactivate.
 * Everything under /owner is limited to the OWNER role by {@code SecurityConfig}.
 *
 * <p>There is no delete. Purchases reference products by foreign key, so a
 * product that has ever been sold could not be removed without destroying sales
 * history; deactivating it takes it out of the catalog and keeps the record.
 */
@Controller
@RequestMapping("/owner/products")
public class OwnerProductController {

    private static final Logger log = LoggerFactory.getLogger(OwnerProductController.class);

    private final ProductService productService;

    /**
     * Creates the controller.
     *
     * @param productService reads and changes the inventory
     */
    public OwnerProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * Lists every product, inactive ones included, with its price, stock and status.
     *
     * @param model receives the products
     * @return the inventory view
     */
    @GetMapping
    public String list(Model model) {
        log.info("GET /owner/products - listing inventory");
        model.addAttribute("products", productService.findAll());
        return "owner/products";
    }

    /**
     * Renders a blank form for adding a product.
     *
     * @param model receives the empty form and the page settings
     * @return the product form view
     */
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("productForm", new ProductForm());
        populateForm(model, "Add Product", "/owner/products");
        return "owner/product-form";
    }

    /**
     * Adds a product to the shop, or redisplays the form with field errors.
     *
     * @param form               the submitted product
     * @param binding            the validation result for the form
     * @param model              receives the page settings if the form is redisplayed
     * @param redirectAttributes carries the success message to the inventory page
     * @return a redirect to the inventory on success, otherwise the product form view
     */
    @PostMapping
    public String create(@Valid @ModelAttribute("productForm") ProductForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        if (binding.hasErrors()) {
            log.warn("POST /owner/products - create rejected with {} validation error(s)", binding.getErrorCount());
            populateForm(model, "Add Product", "/owner/products");
            return "owner/product-form";
        }
        Product saved = productService.create(form);
        redirectAttributes.addFlashAttribute("message", "Added " + saved.getName() + ".");
        return "redirect:/owner/products";
    }

    /**
     * Renders the form pre-filled with an existing product.
     *
     * @param id    the product id from the URL
     * @param model receives the filled form and the page settings
     * @return the product form view
     */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("productForm", ProductForm.from(productService.findById(id)));
        populateForm(model, "Edit Product", "/owner/products/" + id);
        return "owner/product-form";
    }

    /**
     * Saves changes to a product, or redisplays the form with field errors.
     *
     * @param id                 the product id from the URL
     * @param form               the submitted product
     * @param binding            the validation result for the form
     * @param model              receives the page settings if the form is redisplayed
     * @param redirectAttributes carries the success message to the inventory page
     * @return a redirect to the inventory on success, otherwise the product form view
     */
    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("productForm") ProductForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        if (binding.hasErrors()) {
            log.warn("POST /owner/products/{} - update rejected with {} validation error(s)", id, binding.getErrorCount());
            populateForm(model, "Edit Product", "/owner/products/" + id);
            return "owner/product-form";
        }
        Product saved = productService.update(id, form);
        redirectAttributes.addFlashAttribute("message", "Updated " + saved.getName() + ".");
        return "redirect:/owner/products";
    }

    /**
     * Takes a product out of the public catalog. A POST, so a link prefetch cannot fire it.
     *
     * @param id                 the product id from the URL
     * @param redirectAttributes carries the success message to the inventory page
     * @return a redirect to the inventory
     */
    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Product product = productService.deactivate(id);
        redirectAttributes.addFlashAttribute("message",
                "Deactivated " + product.getName() + ". It is no longer shown in the catalog.");
        return "redirect:/owner/products";
    }

    /**
     * Returns a deactivated product to the public catalog.
     *
     * @param id                 the product id from the URL
     * @param redirectAttributes carries the success message to the inventory page
     * @return a redirect to the inventory
     */
    @PostMapping("/{id}/reactivate")
    public String reactivate(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Product product = productService.reactivate(id);
        redirectAttributes.addFlashAttribute("message",
                "Reactivated " + product.getName() + ". It is back in the catalog.");
        return "redirect:/owner/products";
    }

    /** One template serves both create and edit; these two values are all that differ. */
    private void populateForm(Model model, String pageTitle, String formAction) {
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("formAction", formAction);
    }
}
