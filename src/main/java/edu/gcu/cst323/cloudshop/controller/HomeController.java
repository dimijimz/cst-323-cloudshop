package edu.gcu.cst323.cloudshop.controller;

import edu.gcu.cst323.cloudshop.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the public landing page. */
@Controller
public class HomeController {

    private static final Logger log = LoggerFactory.getLogger(HomeController.class);

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
        log.info("GET / - rendering home page");
        model.addAttribute("productCount", productService.findCatalog().size());
        return "index";
    }
}
