package edu.gcu.cst323.cloudshop.controller;

import edu.gcu.cst323.cloudshop.service.ProductService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the public landing page.
 *
 * @author Dimitri Jimenez
 */
@Controller
public class HomeController {

    /** Counts the products in the catalog, for the line on the home page. */
    private final ProductService productService;

    /**
     * Creates the controller.
     *
     * @param productService used to say how many products the catalog holds
     */
    public HomeController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * Renders the home page, which introduces the shop and links to the catalog,
     * sign-in and sign-up.
     *
     * @param model receives the size of the catalog
     * @return the home view
     */
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("productCount", productService.findCatalog().size());
        return "index";
    }
}
