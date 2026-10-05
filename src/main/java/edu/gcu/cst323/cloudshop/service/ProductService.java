package edu.gcu.cst323.cloudshop.service;

import edu.gcu.cst323.cloudshop.exception.ResourceNotFoundException;
import edu.gcu.cst323.cloudshop.form.ProductForm;
import edu.gcu.cst323.cloudshop.model.Product;
import edu.gcu.cst323.cloudshop.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Business layer for the catalog and the owner's inventory.
 *
 * <p>Two views of the same table. The catalog methods only ever return active
 * products, so a deactivated product cannot be reached from a public page; the
 * inventory methods return everything. Every change is logged at INFO to stdout,
 * which is what the cloud platforms' log collectors read.
 */
@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;

    /**
     * Creates the service.
     *
     * @param productRepository data access for products
     */
    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Lists the public catalog.
     *
     * @return every active product by name, including those that are out of stock
     */
    public List<Product> findCatalog() {
        return productRepository.findByActiveTrueOrderByNameAsc();
    }

    /**
     * Lists the owner's inventory.
     *
     * @return every product by name, inactive ones included
     */
    public List<Product> findAll() {
        return productRepository.findAllByOrderByNameAsc();
    }

    /**
     * Finds a product for a public page.
     *
     * @param id the product id
     * @return the product
     * @throws ResourceNotFoundException if it does not exist or has been deactivated
     */
    public Product findActiveById(Long id) {
        return productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    /**
     * Finds a product for an owner page, whether or not it is active.
     *
     * @param id the product id
     * @return the product
     * @throws ResourceNotFoundException if it does not exist
     */
    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    /**
     * Adds a product to the shop. It starts out active.
     *
     * @param form the validated product form
     * @return the saved product
     */
    @Transactional
    public Product create(ProductForm form) {
        Product saved = productRepository.save(new Product(
                form.getName().trim(),
                form.getDescription().trim(),
                form.getPrice(),
                form.getStock()));
        log.info("CREATE product: id={} name={} price={} stock={}",
                saved.getId(), saved.getName(), saved.getPrice(), saved.getStock());
        return saved;
    }

    /**
     * Changes a product's name, description, price and stock. Past purchases are
     * unaffected, because each one stores the price it was made at.
     *
     * @param id   the product to change
     * @param form the validated product form
     * @return the updated product
     * @throws ResourceNotFoundException if the product does not exist
     */
    @Transactional
    public Product update(Long id, ProductForm form) {
        Product product = findById(id);
        product.setName(form.getName().trim());
        product.setDescription(form.getDescription().trim());
        product.setPrice(form.getPrice());
        product.setStock(form.getStock());
        log.info("UPDATE product: id={} name={} price={} stock={}",
                product.getId(), product.getName(), product.getPrice(), product.getStock());
        return product;
    }

    /**
     * Takes a product out of the public catalog without deleting it, so the
     * purchases that reference it stay intact.
     *
     * @param id the product to deactivate
     * @return the deactivated product
     * @throws ResourceNotFoundException if the product does not exist
     */
    @Transactional
    public Product deactivate(Long id) {
        return setActive(id, false);
    }

    /**
     * Returns a deactivated product to the public catalog.
     *
     * @param id the product to reactivate
     * @return the reactivated product
     * @throws ResourceNotFoundException if the product does not exist
     */
    @Transactional
    public Product reactivate(Long id) {
        return setActive(id, true);
    }

    private Product setActive(Long id, boolean active) {
        Product product = findById(id);
        product.setActive(active);
        log.info("UPDATE product: id={} name={} active={}", product.getId(), product.getName(), active);
        return product;
    }
}
