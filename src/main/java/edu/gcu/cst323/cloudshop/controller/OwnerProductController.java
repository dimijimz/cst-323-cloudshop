package edu.gcu.cst323.cloudshop.controller;

import edu.gcu.cst323.cloudshop.exception.ResourceNotFoundException;
import edu.gcu.cst323.cloudshop.form.ProductForm;
import edu.gcu.cst323.cloudshop.logging.ErrorCode;
import edu.gcu.cst323.cloudshop.logging.LogValues;
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
 *
 * @author Dimitri Jimenez
 */
@Controller
@RequestMapping(OwnerProductController.INVENTORY_URL)
public class OwnerProductController {

    /**
     * Where the inventory is; every route in this controller is under it. Public
     * because the class's own {@code @RequestMapping} names it, and an annotation on
     * a class cannot see that class's private members.
     */
    public static final String INVENTORY_URL = "/owner/products";

    /** Sends the owner back to the inventory list after a change. */
    private static final String REDIRECT_TO_INVENTORY = "redirect:" + INVENTORY_URL;

    /** The one template that serves both the create and the edit form. */
    private static final String PRODUCT_FORM_VIEW = "owner/product-form";

    /** The form's heading when a product is being added. */
    private static final String ADD_PRODUCT_TITLE = "Add Product";

    /** The form's heading when a product is being edited. */
    private static final String EDIT_PRODUCT_TITLE = "Edit Product";

    /** Records each rejected product form, naming the fields at fault. */
    private static final Logger log = LoggerFactory.getLogger(OwnerProductController.class);

    /** Reads and changes the inventory. */
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
        populateForm(model, ADD_PRODUCT_TITLE, INVENTORY_URL);
        return PRODUCT_FORM_VIEW;
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
            log.warn("REJECTED POST /owner/products [{}] {}",
                    ErrorCode.VALIDATION.label(), LogValues.fieldErrors(binding));
            populateForm(model, ADD_PRODUCT_TITLE, INVENTORY_URL);
            return PRODUCT_FORM_VIEW;
        }
        Product saved = productService.create(form);
        redirectAttributes.addFlashAttribute("message", "Added " + saved.getName() + ".");
        return REDIRECT_TO_INVENTORY;
    }

    /**
     * Renders the form pre-filled with an existing product.
     *
     * @param id    the product id from the URL
     * @param model receives the filled form and the page settings
     * @return the product form view
     * @throws ResourceNotFoundException if the product does not exist
     */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("productForm", ProductForm.from(productService.findById(id)));
        populateForm(model, EDIT_PRODUCT_TITLE, INVENTORY_URL + "/" + id);
        return PRODUCT_FORM_VIEW;
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
     * @throws ResourceNotFoundException if the product does not exist
     */
    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("productForm") ProductForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        if (binding.hasErrors()) {
            log.warn("REJECTED POST /owner/products/{id} [{}] {} (id={})",
                    ErrorCode.VALIDATION.label(), LogValues.fieldErrors(binding), id);
            populateForm(model, EDIT_PRODUCT_TITLE, INVENTORY_URL + "/" + id);
            return PRODUCT_FORM_VIEW;
        }
        Product saved = productService.update(id, form);
        redirectAttributes.addFlashAttribute("message", "Updated " + saved.getName() + ".");
        return REDIRECT_TO_INVENTORY;
    }

    /**
     * Takes a product out of the public catalog. A POST, so a link prefetch cannot fire it.
     *
     * @param id                 the product id from the URL
     * @param redirectAttributes carries the success message to the inventory page
     * @return a redirect to the inventory
     * @throws ResourceNotFoundException if the product does not exist
     */
    @PostMapping("/{id}/deactivate")
    public String deactivate(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Product product = productService.deactivate(id);
        redirectAttributes.addFlashAttribute("message",
                "Deactivated " + product.getName() + ". It is no longer shown in the catalog.");
        return REDIRECT_TO_INVENTORY;
    }

    /**
     * Returns a deactivated product to the public catalog.
     *
     * @param id                 the product id from the URL
     * @param redirectAttributes carries the success message to the inventory page
     * @return a redirect to the inventory
     * @throws ResourceNotFoundException if the product does not exist
     */
    @PostMapping("/{id}/reactivate")
    public String reactivate(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Product product = productService.reactivate(id);
        redirectAttributes.addFlashAttribute("message",
                "Reactivated " + product.getName() + ". It is back in the catalog.");
        return REDIRECT_TO_INVENTORY;
    }

    /**
     * Sets the two values that differ between the create and the edit page, which
     * share one template.
     *
     * @param model      the model the page is rendered from
     * @param pageTitle  the heading, "Add Product" or "Edit Product"
     * @param formAction the address the form posts to: the inventory for a new
     *                   product, the product's own address for an edit
     */
    private void populateForm(Model model, String pageTitle, String formAction) {
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("formAction", formAction);
    }
}
