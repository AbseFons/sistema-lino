package com.tienda.inventario.app;

import com.tienda.inventario.application.AuthService;
import com.tienda.inventario.application.CatalogService;
import com.tienda.inventario.application.DecimalUnits;
import com.tienda.inventario.application.ProductCatalogService;
import com.tienda.inventario.application.ProductService;
import com.tienda.inventario.application.ProductMaintenanceService;
import com.tienda.inventario.application.UserService;
import com.tienda.inventario.application.SupplierService;
import com.tienda.inventario.application.PurchaseService;
import com.tienda.inventario.application.CustomerService;
import com.tienda.inventario.application.SaleService;
import com.tienda.inventario.application.DemandService;
import com.tienda.inventario.application.ReportsService;
import com.tienda.inventario.presentation.ReportsPage;
import com.tienda.inventario.presentation.BackupPage;
import com.tienda.inventario.presentation.DemandPage;
import com.tienda.inventario.domain.CatalogItem;
import com.tienda.inventario.domain.CatalogEntry;
import com.tienda.inventario.domain.NewProduct;
import com.tienda.inventario.domain.Product;
import com.tienda.inventario.domain.ProductCompatibility;
import com.tienda.inventario.domain.ProductDetails;
import com.tienda.inventario.domain.ProductPresentation;
import com.tienda.inventario.domain.Session;
import com.tienda.inventario.domain.ProductEdit;
import com.tienda.inventario.domain.PriceHistoryEntry;
import com.tienda.inventario.domain.StockHistoryEntry;
import com.tienda.inventario.domain.Supplier;
import com.tienda.inventario.domain.PurchasePresentation;
import com.tienda.inventario.domain.PurchaseEntry;
import com.tienda.inventario.domain.PurchaseDetailEntry;
import com.tienda.inventario.domain.PurchaseLineInput;
import com.tienda.inventario.domain.NewPurchase;
import com.tienda.inventario.domain.PurchaseResult;
import com.tienda.inventario.domain.Customer;
import com.tienda.inventario.domain.PriceList;
import com.tienda.inventario.domain.SalePresentation;
import com.tienda.inventario.domain.SaleLineInput;
import com.tienda.inventario.domain.SalePaymentInput;
import com.tienda.inventario.domain.NewSale;
import com.tienda.inventario.domain.SaleResult;
import com.tienda.inventario.domain.SaleEntry;
import com.tienda.inventario.domain.SaleDetailEntry;
import com.tienda.inventario.domain.SalePaymentEntry;
import com.tienda.inventario.infrastructure.database.Database;
import com.tienda.inventario.infrastructure.database.DatabasePaths;
import com.tienda.inventario.infrastructure.database.BackupService;
import com.tienda.inventario.infrastructure.database.InstanceLock;
import com.tienda.inventario.infrastructure.repository.CatalogRepository;
import com.tienda.inventario.infrastructure.repository.ProductRepository;
import javafx.application.Application;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** First functional milestone: secure setup/login and initial product registration. */
public final class InventarioApp extends Application {
    private Stage mainWindow;
    private Database db;
    private BackupService backups;
    private InstanceLock instanceLock;
    private volatile String backupWarning;
    private ScheduledExecutorService dailyBackups;
    private AuthService auth;
    private ProductService products;
    private ProductMaintenanceService maintenance;
    private CatalogService catalogService;
    private ProductCatalogService productCatalogService;
    private UserService users;
    private SupplierService supplierService;
    private PurchaseService purchaseService;
    private CustomerService customerService;
    private SaleService saleService;
    private DemandService demandService;
    private ReportsService reportsService;
    private ProductRepository productRepo;
    private CatalogRepository catalogs;
    private Session session;

    @Override public void start(Stage stage) {
        mainWindow = stage;
        stage.setTitle("Inventario Repuestos · Administración");
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        try {
            var databasePath = DatabasePaths.resolve();
            instanceLock = InstanceLock.acquire(databasePath);
            backups = new BackupService(databasePath);
            boolean restoredAtStartup = backups.restorePendingAtStartup() != null; // antes de Flyway
            db = new Database(databasePath);
            db.initialize();
            auth = new AuthService(db);
            products = new ProductService(db);
            maintenance = new ProductMaintenanceService(db);
            catalogService = new CatalogService(db);
            productCatalogService = new ProductCatalogService(db);
            users = new UserService(db);
            supplierService = new SupplierService(db);
            purchaseService = new PurchaseService(db);
            customerService = new CustomerService(db);
            saleService = new SaleService(db);
            demandService = new DemandService(db);
            reportsService = new ReportsService(db);
            productRepo = new ProductRepository(db);
            catalogs = new CatalogRepository(db);
            if (auth.requiresBootstrap()) showFirstAdmin();
            else {
                try { backups.ensureDailyBackup(); } catch(Exception warning){backupWarning=warning.getMessage();}
                if (restoredAtStartup) {
                    try { backups.createPostRestoreBackup(); }
                    catch(Exception warning){backupWarning=warning.getMessage();}
                }
                startAutomaticBackupChecks();
                showLogin();
            }
        } catch (Exception e) {
            showError("No se pudo iniciar", e);
            javafx.application.Platform.exit();
        }
    }

    private void startAutomaticBackupChecks() {
        if (dailyBackups != null) return;
        dailyBackups = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread worker=new Thread(task,"respaldo-diario-automatico");worker.setDaemon(true);return worker;
        });
        // Al abrir ya se genera la copia. Si la aplicación permanece abierta días,
        // una comprobación horaria sigue generando una por día comercial (America/Lima).
        dailyBackups.scheduleAtFixedRate(() -> {
            try { backups.ensureDailyBackup();backupWarning=null; }
            catch(Exception warning){backupWarning=warning.getMessage();}
        }, 1,1,TimeUnit.HOURS);
    }

    @Override public void stop() throws Exception {
        if(dailyBackups != null) dailyBackups.shutdownNow();
        try { if(instanceLock != null) instanceLock.close(); }
        finally { super.stop(); }
    }

    private void useRoot(Pane root) {
        Scene scene = new Scene(root, 1120, 730);
        scene.getStylesheets().add(getClass().getResource("/ui/theme.css").toExternalForm());
        mainWindow.setScene(scene);
        mainWindow.show();
    }

    private void showFirstAdmin() {
        Label eyebrow = overline("CONFIGURACIÓN INICIAL");
        Label title = new Label("Crea el primer administrador");
        title.getStyleClass().add("screen-title");
        Label sub = muted("La base se creó en " + db.path() + ". No existen claves predeterminadas.");
        sub.setWrapText(true);
        TextField name = input("Nombre completo");
        TextField username = input("Usuario de acceso (ej.: administrador)");
        PasswordField pass = password("Contraseña (12 caracteres mínimo)");
        PasswordField confirm = password("Repetir contraseña");
        Button create = primary("Crear administrador");
        create.setMaxWidth(Double.MAX_VALUE);
        create.setOnAction(e -> {
            if (!pass.getText().equals(confirm.getText())) {
                warn("Las contraseñas no coinciden"); return;
            }
            char[] chars = pass.getText().toCharArray();
            try {
                auth.createFirstAdmin(name.getText(),username.getText(),chars);
                try { backups.ensureDailyBackup(); } catch(Exception warning){backupWarning=warning.getMessage();}
                startAutomaticBackupChecks();
                showLogin();
            } catch (Exception ex) { showError("No se pudo crear el administrador",ex); }
            finally { Arrays.fill(chars,'\0'); pass.clear(); confirm.clear(); }
        });
        VBox card = new VBox(16, eyebrow,title,sub,labeled("Nombre",name),labeled("Usuario",username),
                labeled("Contraseña",pass),labeled("Confirmación",confirm),create);
        card.setPadding(new Insets(35)); card.setMaxWidth(460); card.getStyleClass().add("auth-card");
        StackPane root = new StackPane(card); root.getStyleClass().add("auth-background");
        useRoot(root);
    }

    private void showLogin() {
        Label title = new Label("Bienvenido"); title.getStyleClass().add("screen-title");
        Label sub = muted("Ingresa con tu cuenta individual para continuar.");
        TextField username = input("Tu usuario");
        PasswordField pass = password("Tu contraseña");
        Button login = primary("Ingresar"); login.setMaxWidth(Double.MAX_VALUE);
        Runnable authenticate = () -> {
            char[] chars = pass.getText().toCharArray();
            try {
                var authenticated = auth.authenticate(username.getText(),chars);
                if (authenticated.isPresent()) { session = authenticated.get(); showWorkspace("inicio"); }
                else warn("Usuario o contraseña incorrectos");
            } catch (Exception ex) { showError("Error al ingresar",ex); }
            finally { Arrays.fill(chars,'\0'); pass.clear(); }
        };
        login.setOnAction(e -> authenticate.run());
        pass.setOnAction(e -> authenticate.run());
        VBox card = new VBox(19,overline("INVENTARIO Y FACTURACIÓN"),title,sub,
                labeled("Usuario",username),labeled("Contraseña",pass),login);
        card.setPadding(new Insets(35)); card.setMaxWidth(460); card.getStyleClass().add("auth-card");
        StackPane root = new StackPane(card); root.getStyleClass().add("auth-background");
        useRoot(root);
    }

    private void showWorkspace(String page) {
        if (session == null) { showLogin(); return; }
        BorderPane root = new BorderPane(); root.getStyleClass().add("app-root");
        VBox sidebar = new VBox(11); sidebar.setPrefWidth(250); sidebar.getStyleClass().add("sidebar");
        Label brand = new Label("◼  REPUESTOS"); brand.getStyleClass().add("brand");
        Label subtitle = muted("Gestión comercial · v0.7.1");
        VBox brandBlock = new VBox(5,brand,subtitle); brandBlock.setPadding(new Insets(8,0,24,0));
        Button home = navButton("⌂    Inicio", "inicio".equals(page), () -> showWorkspace("inicio"));
        Button prod = navButton("▣    Productos", "productos".equals(page), () -> showWorkspace("productos"));
        sidebar.getChildren().addAll(brandBlock,home,prod,
            navButton("▥    Ventas", "ventas".equals(page), () -> showWorkspace("ventas")),
            navButton("♧    Clientes", "clientes".equals(page), () -> showWorkspace("clientes")),
            navButton("▤    Solicitudes", "solicitudes".equals(page), () -> showWorkspace("solicitudes")),
            navButton("▥    Reportes", "reportes".equals(page), () -> showWorkspace("reportes")));
        if (session.isAdmin()) {
            Button catalogsButton = navButton("▤    Catálogos", "catalogos".equals(page), () -> showWorkspace("catalogos"));
            Button suppliersButton = navButton("▧    Proveedores", "proveedores".equals(page), () -> showWorkspace("proveedores"));
            Button purchasesButton = navButton("▦    Compras", "compras".equals(page), () -> showWorkspace("compras"));
            Button usersButton = navButton("♙    Trabajadores", "usuarios".equals(page), () -> showWorkspace("usuarios"));
            sidebar.getChildren().addAll(catalogsButton,suppliersButton,purchasesButton,usersButton,
                    navButton("▣    Respaldos", "respaldos".equals(page), () -> showWorkspace("respaldos")));
        }
        Region push = new Region(); VBox.setVgrow(push,Priority.ALWAYS);
        Label identity = new Label(session.displayName()); identity.getStyleClass().add("sidebar-user");
        Label role = muted(session.isAdmin()?"Administrador":"Vendedor");
        Button logout = secondary("Cerrar sesión"); logout.setMaxWidth(Double.MAX_VALUE);
        logout.setOnAction(e -> { session = null; showLogin(); });
        sidebar.getChildren().addAll(push,new VBox(4,identity,role),logout);
        root.setLeft(sidebar);
        try {
            Node body = switch (page) {
                case "productos" -> productsPage();
                case "catalogos" -> catalogsPage();
                case "usuarios" -> usersPage();
                case "proveedores" -> suppliersPage();
                case "compras" -> purchasesPage();
                case "clientes" -> customersPage();
                case "ventas" -> salesPage();
                case "solicitudes" -> new DemandPage(demandService,session,mainWindow,
                    getClass().getResource("/ui/theme.css").toExternalForm()).build();
                case "reportes" -> new ReportsPage(reportsService,demandService,db,session.isAdmin(),mainWindow).build();
                case "respaldos" -> session.isAdmin()
                        ? new BackupPage(backups,mainWindow,javafx.application.Platform::exit,backupWarning).build()
                        : new VBox(screenTitle("Sin acceso"));
                default -> homePage();
            };
            root.setCenter(body);
        } catch (Exception e) {
            VBox fallback = new VBox(14,new Label("No se pudo cargar esta sección"),muted(e.getMessage()));
            fallback.setPadding(new Insets(30)); root.setCenter(fallback);
        }
        useRoot(root);
    }

    private Node homePage() throws Exception {
        Label title = screenTitle("Panel general");
        Label note = muted("Productos, proveedores, compras, ventas, reportes y solicitudes no atendidas.");
        LocalDate today = LocalDate.now(ReportsService.BUSINESS_ZONE);
        HBox cards = new HBox(14,
                summaryCard("Productos registrados", Integer.toString(productRepo.total()), "Catálogo local"),
                summaryCard("Alertas de inventario", Integer.toString(productRepo.lowStock()), "Stock bajo o agotado"),
                summaryCard("Ventas de hoy", soles(reportsService.summary(today,today).revenueCents()), "Ventas confirmadas"),
                summaryCard("Solicitudes pendientes", Long.toString(demandService.countPending()), "Demanda no atendida"));
        for (Node card : cards.getChildren()) HBox.setHgrow(card,Priority.ALWAYS);
        Label next = new Label("Gestión comercial"); next.getStyleClass().add("section-heading");
        Label body = muted("Consulta los reportes de ventas o registra productos solicitados que no tenías disponibles. " +
                "Los comprobantes siguen PENDIENTES: no existe emisión tributaria SUNAT en esta versión.");
        body.setWrapText(true);
        Button goProducts = primary("Abrir reportes"); goProducts.setOnAction(e -> showWorkspace("reportes"));
        VBox info = new VBox(12,next,body,goProducts); info.getStyleClass().add("information-panel");
        VBox content = new VBox(24,title,note,cards,info);
        if (session.isAdmin() && backupWarning != null) {
            Label backupNotice = muted("⚠ No se completó el respaldo automático: " + backupWarning +
                    ". Abre Respaldos para revisar y crear una copia manual.");
            backupNotice.setWrapText(true);
            content.getChildren().add(1,backupNotice);
        }
        content.setPadding(new Insets(38));
        return content;
    }

    private Node productsPage() throws Exception {
        Label title = screenTitle("Productos");
        Label description = muted("Busca productos, edita sus datos, revisa precios y controla el inventario sin perder el historial.");
        Button add = primary("+  Nuevo producto");
        add.setDisable(!session.isAdmin());
        add.setOnAction(e -> newProductDialog());
        HBox header = new HBox(18,new VBox(5,title,description),new Region(),add);
        HBox.setHgrow(header.getChildren().get(1),Priority.ALWAYS);
        header.setAlignment(Pos.CENTER_LEFT);
        TextField search = input("Buscar por SKU o nombre..."); search.setPrefWidth(340);
        Button refresh = secondary("Actualizar");
        HBox actions = new HBox(12,search,refresh); actions.setAlignment(Pos.CENTER_LEFT);
        TableView<Product> table = new TableView<>(); table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(muted("No existen productos o no coinciden con la búsqueda"));
        table.getColumns().addAll(
                column("SKU", Product::sku), column("Nombre",Product::name),
                column("Categoría",Product::category),column("Marca",Product::brand),
                column("Stock",p->DecimalUnits.displayMilli(p.stockMilli())+" "+p.unit()),
                column("Mínimo",p->DecimalUnits.displayMilli(p.minimumMilli())),
                column("Alerta",Product::alert),column("Precio público",p->DecimalUnits.displayPrice(p.publicPriceMicro()))
        );
        Button edit = secondary("Editar producto");
        Button prices = secondary("Precios");
        Button adjust = secondary("Ajustar stock");
        Button histories = secondary("Historial");
        Button metadata = secondary("Ficha adicional");
        Button presentations = secondary("Presentaciones");
        Button compat = secondary("Compatibilidades");
        Button status = secondary("Activar / Desactivar");
        for(Button b: List.of(edit,prices,adjust,histories,metadata,presentations,compat,status)) b.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((obs,oldValue,value) -> {
            boolean has=value!=null;
            for(Button b: List.of(edit,prices,adjust,metadata,presentations,compat,status))
                b.setDisable(!has || !session.isAdmin());
            histories.setDisable(!has);
        });
        edit.setOnAction(e->{Product p=table.getSelectionModel().getSelectedItem();if(p!=null)editProductDialog(p);});
        prices.setOnAction(e->{Product p=table.getSelectionModel().getSelectedItem();if(p!=null)pricesDialog(p);});
        adjust.setOnAction(e->{Product p=table.getSelectionModel().getSelectedItem();if(p!=null)adjustStockDialog(p);});
        histories.setOnAction(e->{Product p=table.getSelectionModel().getSelectedItem();if(p!=null)historyDialog(p);});
        status.setOnAction(e->{
            Product p=table.getSelectionModel().getSelectedItem();
            if(p==null) return;
            boolean activate="INACTIVO".equals(p.status());
            if(!confirm("¿"+(activate?"Activar":"Desactivar")+" '"+p.name()+"'? Se conservarán su historial y su stock."))return;
            try {maintenance.setProductActive(p.id(),activate,session);showWorkspace("productos");}
            catch(Exception ex){showError("No se pudo cambiar el estado",ex);}
        });
        metadata.setOnAction(e->{Product p=table.getSelectionModel().getSelectedItem(); if(p!=null) metadataDialog(p);});
        presentations.setOnAction(e->{Product p=table.getSelectionModel().getSelectedItem(); if(p!=null) presentationsDialog(p);});
        compat.setOnAction(e->{Product p=table.getSelectionModel().getSelectedItem(); if(p!=null) compatibilitiesDialog(p);});
        Runnable load = () -> {
            try { table.setItems(FXCollections.observableArrayList(productRepo.search(search.getText()))); }
            catch (Exception e) { showError("No se pudo consultar los productos",e); }
        };
        search.textProperty().addListener((obs,oldValue,newValue)->load.run());
        refresh.setOnAction(e->load.run()); load.run();
        HBox operations = new HBox(8,edit,prices,adjust,histories,metadata,presentations,compat,status);
        operations.setAlignment(Pos.CENTER_LEFT);operations.setSpacing(8);
        operations.setMaxWidth(Double.MAX_VALUE);
        operations.setStyle("-fx-padding: 0 0 2 0;");
        ScrollPane operationsScroll = new ScrollPane(operations);
        operationsScroll.setFitToHeight(true);operationsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        operationsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        operationsScroll.setPrefHeight(65);
        VBox content = new VBox(20,header,actions,table,operationsScroll); VBox.setVgrow(table,Priority.ALWAYS);
        content.setPadding(new Insets(34));
        return content;
    }

    private Node catalogsPage() throws Exception {
        if(!session.isAdmin()) return new VBox(screenTitle("Sin acceso"));
        Label title=screenTitle("Categorías y marcas");
        Label info=muted("Los catálogos se comparten entre productos. Se desactivan, no se borran. Los cambios quedan auditados.");
        info.setWrapText(true);
        TabPane tabs=new TabPane();
        tabs.getTabs().addAll(catalogTab(false),catalogTab(true));
        VBox panel=new VBox(16,title,info,tabs); VBox.setVgrow(tabs,Priority.ALWAYS);
        panel.setPadding(new Insets(34)); return panel;
    }

    private Tab catalogTab(boolean brand) throws Exception {
        String what=brand?"marca":"categoría";
        TableView<CatalogEntry> table=new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().addAll(
                genericColumn("Identificador",x->Long.toString(x.id())),
                genericColumn("Nombre",CatalogEntry::name),
                genericColumn("Estado",x->x.active()?"Activa":"Inactiva"));
        Runnable load=()->{
            try { table.setItems(FXCollections.observableArrayList(brand?catalogs.allBrands():catalogs.allCategories())); }
            catch(Exception ex){showError("No se pudo cargar el catálogo",ex);}
        };
        Button create=primary("+ Nueva "+what);
        Button rename=secondary("Renombrar"); Button state=secondary("Activar / Desactivar");
        rename.setDisable(true);state.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((ob,old,value)->{
            rename.setDisable(value==null);state.setDisable(value==null);
        });
        create.setOnAction(e->{
            new TextInputDialog().showAndWait().ifPresent(value->{
                try { if(brand) catalogService.createBrand(value,session); else catalogService.createCategory(value,session); load.run(); }
                catch(Exception ex){showError("No se pudo crear la "+what,ex);}
            });
        });
        rename.setOnAction(e->{
            CatalogEntry chosen=table.getSelectionModel().getSelectedItem(); if(chosen==null)return;
            String original=chosen.name();
            TextInputDialog editor=new TextInputDialog(original);editor.setTitle("Renombrar "+what);
            editor.setHeaderText("Nuevo nombre para "+original);
            editor.showAndWait().ifPresent(value->{
                try { if(brand)catalogService.renameBrand(chosen.id(),value,session);
                    else catalogService.renameCategory(chosen.id(),value,session);load.run(); }
                catch(Exception ex){showError("No se pudo modificar el catálogo",ex);}
            });
        });
        state.setOnAction(e->{
            CatalogEntry chosen=table.getSelectionModel().getSelectedItem(); if(chosen==null)return;
            boolean activate=!chosen.active();
            if(!activate && !confirm("¿Desactivar "+chosen.name()+"? No podrá seleccionarse en productos nuevos."))return;
            try {if(brand)catalogService.setBrandActive(chosen.id(),activate,session);
                else catalogService.setCategoryActive(chosen.id(),activate,session);load.run();}
            catch(Exception ex){showError("No se pudo cambiar el estado",ex);}
        });
        HBox actions=new HBox(12,create,rename,state);
        VBox body=new VBox(15,actions,table);body.setPadding(new Insets(18));VBox.setVgrow(table,Priority.ALWAYS);
        Tab tab=new Tab(brand?"Marcas":"Categorías",body);tab.setClosable(false);
        load.run(); return tab;
    }

    private Node suppliersPage() throws Exception {
        Label title=screenTitle("Proveedores");
        Label info=muted("Consulta, registra y administra proveedores. Un proveedor usado en compras mantiene su historial.");
        TableView<Supplier> table=new TableView<>();table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().addAll(genericColumn("Razón social",Supplier::name),
                genericColumn("RUC",s->s.ruc()==null?"—":s.ruc()),
                genericColumn("Teléfono",s->s.phone()==null?"—":s.phone()),
                genericColumn("Estado",s->s.active()?"Activo":"Inactivo"));
        table.setItems(FXCollections.observableArrayList(supplierService.findAll()));
        Button create=primary("+ Nuevo proveedor"),edit=secondary("Editar"),toggle=secondary("Activar / Desactivar");
        edit.setDisable(true);toggle.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o,old,v)->{
            edit.setDisable(v==null);toggle.setDisable(v==null);
        });
        create.setOnAction(e->supplierDialog(null));
        edit.setOnAction(e->{Supplier chosen=table.getSelectionModel().getSelectedItem();if(chosen!=null)supplierDialog(chosen);});
        toggle.setOnAction(e->{
            Supplier chosen=table.getSelectionModel().getSelectedItem();
            if(chosen!=null&&confirm("¿"+(chosen.active()?"Desactivar":"Activar")+" a "+chosen.name()+"?")){
                try{supplierService.setActive(chosen.id(),!chosen.active(),session);showWorkspace("proveedores");}
                catch(Exception ex){showError("No se pudo cambiar el estado",ex);}
            }
        });
        HBox actions=new HBox(12,create,edit,toggle);
        VBox box=new VBox(16,title,info,actions,table);box.setPadding(new Insets(35));VBox.setVgrow(table,Priority.ALWAYS);
        return box;
    }

    private void supplierDialog(Supplier existing) {
        Stage pop=popup(existing==null?"Nuevo proveedor":"Editar proveedor");
        TextField name=input("Razón social / nombre"),ruc=input("RUC opcional (11 dígitos)"),
                phone=input("Teléfono opcional");
        if(existing!=null){name.setText(existing.name());ruc.setText(existing.ruc()==null?"":existing.ruc());
            phone.setText(existing.phone()==null?"":existing.phone());}
        Label note=muted("El RUC puede quedar vacío, pero se recomienda consignarlo para reconocer compras de cada proveedor.");
        note.setWrapText(true);
        Button save=primary("Guardar"),cancel=secondary("Cancelar");cancel.setOnAction(e->pop.close());
        save.setOnAction(e->{try{
            if(existing==null)supplierService.create(name.getText(),ruc.getText(),phone.getText(),session);
            else supplierService.update(existing.id(),name.getText(),ruc.getText(),phone.getText(),session);
            pop.close();showWorkspace("proveedores");
        }catch(Exception ex){showError("No se pudo guardar el proveedor",ex);}});
        VBox body=new VBox(15,screenTitle(existing==null?"Registrar proveedor":"Editar proveedor"),note,
                labeled("Razón social / nombre",name),labeled("RUC (opcional)",ruc),
                labeled("Teléfono",phone),new HBox(12,cancel,save));body.setPadding(new Insets(28));
        pop.setScene(styled(body,540,510));pop.showAndWait();
    }

    private Node purchasesPage() throws Exception {
        Label title=screenTitle("Compras e ingresos de inventario");
        Label info=muted("Compras confirmadas. El ingreso al kardex es automático e irreversible desde esta pantalla.");
        info.setWrapText(true);
        TableView<PurchaseEntry> table=new TableView<>();table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().addAll(genericColumn("N.°",p->Long.toString(p.id())),
                genericColumn("Fecha",PurchaseEntry::date),genericColumn("Proveedor",PurchaseEntry::supplierName),
                genericColumn("Documento",p->p.documentNumber()==null?"—":p.documentNumber()),
                genericColumn("Líneas",p->Integer.toString(p.lines())),
                genericColumn("Total",p->soles(p.totalCents())));
        table.setItems(FXCollections.observableArrayList(purchaseService.recentPurchases(200)));
        Button create=primary("+ Registrar compra"),detail=secondary("Ver detalle");detail.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o,old,value)->detail.setDisable(value==null));
        create.setOnAction(e->newPurchaseDialog());
        detail.setOnAction(e->{PurchaseEntry item=table.getSelectionModel().getSelectedItem();if(item!=null)purchaseDetailsDialog(item);});
        Button toSuppliers=secondary("Administrar proveedores");toSuppliers.setOnAction(e->showWorkspace("proveedores"));
        HBox actions=new HBox(12,create,detail,toSuppliers);
        VBox box=new VBox(15,title,info,actions,table);box.setPadding(new Insets(35));VBox.setVgrow(table,Priority.ALWAYS);
        return box;
    }

    private record DraftPurchaseLine(PurchasePresentation presentation,BigDecimal quantity,
                                     BigDecimal unitCost,long totalCents) {}
    private static String soles(long cents){return "S/ "+BigDecimal.valueOf(cents,2).setScale(2).toPlainString();}

    private void newPurchaseDialog() {
        try {
            List<PurchasePresentation> all=purchaseService.buyablePresentations();
            if(all.isEmpty()){
                warn("Antes de registrar compras, crea un producto y una presentación habilitada para compras.");return;
            }
            Stage pop=popup("Nueva compra");
            ComboBox<Supplier> supplier=new ComboBox<>();supplier.setPromptText("Sin proveedor (no recomendado)");
            supplier.setItems(FXCollections.observableArrayList(supplierService.findAll().stream().filter(Supplier::active).toList()));
            supplier.setMaxWidth(Double.MAX_VALUE);
            TextField document=input("Documento del proveedor, opcional");
            DatePicker date=new DatePicker(LocalDate.now());
            TextField filter=input("Filtrar por SKU, producto o presentación...");
            ComboBox<PurchasePresentation> choice=new ComboBox<>();choice.setMaxWidth(Double.MAX_VALUE);
            choice.setItems(FXCollections.observableArrayList(all));
            filter.textProperty().addListener((o,old,value)->{
                String query=value==null?"":value.toLowerCase().trim();
                List<PurchasePresentation> found=all.stream().filter(p->p.toString().toLowerCase().contains(query)).limit(150).toList();
                choice.setItems(FXCollections.observableArrayList(found));choice.setValue(null);
            });
            TextField quantity=input("Cantidad de presentaciones, ej. 2");quantity.setText("1");
            TextField cost=input("Costo unitario S/ (por la presentación)");
            Label previous=muted("Selecciona una presentación para consultar su costo anterior.");
            previous.setWrapText(true);
            choice.valueProperty().addListener((o,old,item)->{
                if(item==null){previous.setText("Selecciona una presentación para consultar su costo anterior.");return;}
                previous.setText("Equivale a "+DecimalUnits.displayMilli(item.factorMilli())+" "+item.unitCode()+
                        " · Último costo: "+DecimalUnits.displayPrice(item.latestCostMicro()));
                cost.setText(item.latestCostMicro()==null?"":BigDecimal.valueOf(item.latestCostMicro(),4).stripTrailingZeros().toPlainString());
            });
            var lines=FXCollections.<DraftPurchaseLine>observableArrayList();
            TableView<DraftPurchaseLine> table=new TableView<>(lines);
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);table.setPrefHeight(250);
            table.getColumns().addAll(genericColumn("Producto / presentación",l->l.presentation().toString()),
                    genericColumn("Cant.",l->l.quantity().stripTrailingZeros().toPlainString()),
                    genericColumn("Costo un.",l->"S/ "+l.unitCost().setScale(2).toPlainString()),
                    genericColumn("Importe",l->soles(l.totalCents())));
            Label total=screenTitle("Total: S/ 0.00");
            Runnable refreshTotal=()->{
                long n=0;for(DraftPurchaseLine line:lines)n=Math.addExact(n,line.totalCents());
                total.setText("Total: "+soles(n));
            };
            Button add=primary("+ Añadir línea");
            add.setOnAction(e->{try{
                PurchasePresentation selected=choice.getValue();
                if(selected==null)throw new IllegalArgumentException("Selecciona una presentación de la lista");
                BigDecimal q=new BigDecimal(quantity.getText().trim()),c=new BigDecimal(cost.getText().trim());
                if(q.signum()<=0)throw new IllegalArgumentException("La cantidad debe ser positiva");
                int decimals=selected.factorMilli()==1000?selected.unitDecimals():0;
                long qtyMilli=DecimalUnits.toMilli(q,decimals);
                long costMicro=DecimalUnits.toPriceMicro(c);
                long subtotal=PurchaseService.lineCents(qtyMilli,costMicro);
                if(subtotal<=0)throw new IllegalArgumentException("El importe debe ser mayor a cero");
                if(lines.stream().anyMatch(l->l.presentation().id()==selected.id()))
                    throw new IllegalArgumentException("La presentación ya está agregada; quítala para modificarla");
                lines.add(new DraftPurchaseLine(selected,q,c,subtotal));refreshTotal.run();
                quantity.setText("1");cost.clear();choice.setValue(null);filter.clear();
            }catch(Exception ex){showError("No se pudo agregar la línea",ex);}});
            Button remove=secondary("Quitar línea seleccionada");
            remove.setOnAction(e->{DraftPurchaseLine row=table.getSelectionModel().getSelectedItem();
                if(row!=null){lines.remove(row);refreshTotal.run();}});
            Button confirmPurchase=primary("Confirmar compra e ingresar stock"),cancel=secondary("Cancelar");
            cancel.setOnAction(e->pop.close());
            confirmPurchase.setOnAction(e->{try{
                if(lines.isEmpty())throw new IllegalArgumentException("Agrega al menos una línea");
                if(date.getValue()==null)throw new IllegalArgumentException("Selecciona la fecha");
                if(!confirm("¿Confirmar esta compra por "+total.getText()+"? Se añadirá stock al kardex. La compra confirmada no podrá editarse."))return;
                List<PurchaseLineInput> values=new ArrayList<>();
                for(DraftPurchaseLine line:lines)values.add(new PurchaseLineInput(line.presentation().id(),line.quantity(),line.unitCost()));
                PurchaseResult result=purchaseService.confirm(new NewPurchase(
                        supplier.getValue()==null?null:supplier.getValue().id(),document.getText(),date.getValue(),values),session);
                pop.close();showWorkspace("compras");
                warn("Compra #"+result.id()+" registrada. "+result.lines()+" línea(s), "+soles(result.totalCents())+
                        ". Stock actualizado automáticamente."+(result.changedCosts()>0?
                        "\nAtención: "+result.changedCosts()+" costo(s) cambiaron; revisa los precios de venta en Productos.":""));
            }catch(Exception ex){showError("No se pudo confirmar la compra",ex);}});
            VBox top=new VBox(10,screenTitle("Nueva compra"),muted("El precio de venta NO cambia automáticamente con el costo."),
                    labeled("Proveedor",supplier),labeled("Documento de compra (opcional)",document),
                    labeled("Fecha de compra",date));
            HBox chosen=new HBox(10,labeled("Cantidad",quantity),labeled("Costo unitario de compra (S/)",cost),add);
            chosen.setAlignment(Pos.BOTTOM_LEFT);
            VBox body=new VBox(14,top,labeled("Buscar presentación",filter),labeled("Presentación",choice),previous,
                    chosen,table,new HBox(12,remove,total),new HBox(12,cancel,confirmPurchase));
            VBox.setVgrow(table,Priority.ALWAYS);body.setPadding(new Insets(25));
            ScrollPane scroll=new ScrollPane(body);scroll.setFitToWidth(true);
            pop.setScene(styled(new StackPane(scroll),1060,830));pop.showAndWait();
        }catch(Exception ex){showError("No se pudo crear una compra",ex);}
    }

    private void purchaseDetailsDialog(PurchaseEntry p){
        try{
            Stage pop=popup("Compra #"+p.id());
            TableView<PurchaseDetailEntry> table=new TableView<>();
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            table.getColumns().addAll(genericColumn("SKU",PurchaseDetailEntry::sku),
                    genericColumn("Producto",PurchaseDetailEntry::product),
                    genericColumn("Presentación",PurchaseDetailEntry::presentation),
                    genericColumn("Cantidad",r->DecimalUnits.displayMilli(r.quantityPresentationMilli())),
                    genericColumn("Unidades base",r->DecimalUnits.displayMilli(r.quantityBaseMilli())),
                    genericColumn("Costo unit.",r->DecimalUnits.displayPrice(r.costUnitMicro())),
                    genericColumn("Importe",r->soles(r.lineCents())));
            table.setItems(FXCollections.observableArrayList(purchaseService.details(p.id())));
            Button close=secondary("Cerrar");close.setOnAction(e->pop.close());
            VBox box=new VBox(14,screenTitle("Compra #"+p.id()),
                    muted("Fecha: "+p.date()+" · Proveedor: "+p.supplierName()+" · Documento: "+
                            (p.documentNumber()==null?"No indicado":p.documentNumber())),
                    table,screenTitle("Total: "+soles(p.totalCents())),close);
            VBox.setVgrow(table,Priority.ALWAYS);box.setPadding(new Insets(24));
            pop.setScene(styled(box,1000,610));pop.showAndWait();
        }catch(Exception ex){showError("No se pudo consultar la compra",ex);}
    }

    // ================== v0.5: Clientes, caja y ventas ==================
    private Node customersPage() throws Exception {
        Label title=screenTitle("Clientes frecuentes");
        Label note=muted("Consumidor final no requiere ficha. Para factura, registra primero un cliente con RUC.");
        TableView<Customer> table=new TableView<>();table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().addAll(genericColumn("Cliente",Customer::name),
                genericColumn("Documento",c->c.documentNumber()==null?"—":c.documentType()+" "+c.documentNumber()),
                genericColumn("Teléfono",c->c.phone()==null?"—":c.phone()),
                genericColumn("Lista",c->c.priceListId()==null?"Público (defecto)":priceListName(c.priceListId())),
                genericColumn("Estado",c->c.active()?"Activo":"Inactivo"));
        table.setItems(FXCollections.observableArrayList(customerService.all()));
        Button add=primary("+ Nuevo cliente"),edit=secondary("Editar / estado");edit.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o,old,v)->edit.setDisable(v==null||!session.isAdmin()));
        add.setOnAction(e->customerDialog(null));
        edit.setOnAction(e->{Customer c=table.getSelectionModel().getSelectedItem();if(c!=null)customerDialog(c);});
        VBox content=new VBox(15,title,note,new HBox(12,add,edit),table);content.setPadding(new Insets(35));
        VBox.setVgrow(table,Priority.ALWAYS);return content;
    }

    private String priceListName(long id){
        try{return customerService.priceLists().stream().filter(l->l.id()==id).map(PriceList::name).findFirst().orElse("Inactiva");}
        catch(Exception e){return "Lista #"+id;}
    }

    private void customerDialog(Customer existing){
        try{
            Stage pop=popup(existing==null?"Registrar cliente":"Editar cliente");
            TextField name=input("Nombre o razón social"),document=input("Número de documento"),
                    address=input("Dirección (opcional)"),phone=input("Teléfono (opcional)");
            ComboBox<String> type=new ComboBox<>(FXCollections.observableArrayList("NATURAL","JURIDICA"));
            type.setValue("NATURAL");
            ComboBox<String> docType=new ComboBox<>(FXCollections.observableArrayList("SIN DOCUMENTO","DNI","RUC","OTRO"));
            docType.setValue("SIN DOCUMENTO");
            ComboBox<PriceList> list=new ComboBox<>(FXCollections.observableArrayList(customerService.priceLists()));
            list.getSelectionModel().selectFirst();list.setDisable(!session.isAdmin());
            CheckBox active=new CheckBox("Cliente activo");active.setSelected(true);active.setDisable(!session.isAdmin());
            if(existing!=null){
                name.setText(existing.name());document.setText(existing.documentNumber()==null?"":existing.documentNumber());
                address.setText(existing.address()==null?"":existing.address());phone.setText(existing.phone()==null?"":existing.phone());
                type.setValue(existing.type());docType.setValue(existing.documentType()==null?"SIN DOCUMENTO":existing.documentType());
                active.setSelected(existing.active());
                list.getItems().stream().filter(p->existing.priceListId()!=null&&p.id()==existing.priceListId())
                        .findFirst().ifPresent(list::setValue);
            }
            Button save=primary("Guardar"),cancel=secondary("Cancelar");cancel.setOnAction(e->pop.close());
            save.setOnAction(e->{try{
                String doc="SIN DOCUMENTO".equals(docType.getValue())?null:docType.getValue();
                Long listId=list.getValue()==null?null:list.getValue().id();
                if(existing==null)customerService.create(type.getValue(),doc,document.getText(),name.getText(),
                        address.getText(),phone.getText(),listId,session);
                else customerService.update(existing.id(),type.getValue(),doc,document.getText(),name.getText(),
                        address.getText(),phone.getText(),listId,active.isSelected(),session);
                pop.close();showWorkspace("clientes");
            }catch(Exception ex){showError("No se pudo guardar cliente",ex);}});
            VBox body=new VBox(12,screenTitle(existing==null?"Nuevo cliente":"Editar cliente"),
                    labeled("Nombre / razón social",name),labeled("Tipo de persona",type),
                    labeled("Documento",docType),labeled("Número",document),labeled("Dirección",address),
                    labeled("Teléfono",phone),labeled("Lista de precios",list),active,new HBox(12,cancel,save));
            body.setPadding(new Insets(25));ScrollPane scroll=new ScrollPane(body);scroll.setFitToWidth(true);
            pop.setScene(styled(new StackPane(scroll),600,750));pop.showAndWait();
        }catch(Exception ex){showError("No se pudo abrir cliente",ex);}
    }

    private Node salesPage() throws Exception {
        Label title=screenTitle("Ventas y caja");
        Label note=muted("Las ventas registran pagos y salidas del kardex. Los comprobantes quedan PENDIENTES; esta versión no envía a SUNAT.");
        note.setWrapText(true);
        TableView<SaleEntry> table=new TableView<>();table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().addAll(genericColumn("N.°",x->Long.toString(x.id())),
                genericColumn("Fecha",SaleEntry::date),genericColumn("Cliente",SaleEntry::customer),
                genericColumn("Total",x->soles(x.totalCents())),
                genericColumn("Tipo",SaleEntry::documentType),genericColumn("CPE",SaleEntry::documentStatus),
                genericColumn("Pagos",x->x.payments()==null?"—":x.payments()));
        table.setItems(FXCollections.observableArrayList(saleService.recent(200)));
        Button add=primary("+ Nueva venta"),detail=secondary("Ver detalle"),clients=secondary("Clientes");
        detail.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o,old,v)->detail.setDisable(v==null));
        add.setOnAction(e->newSaleDialog());
        detail.setOnAction(e->{SaleEntry v=table.getSelectionModel().getSelectedItem();if(v!=null)saleDetailsDialog(v);});
        clients.setOnAction(e->showWorkspace("clientes"));
        VBox body=new VBox(15,title,note,new HBox(12,add,detail,clients),table);
        body.setPadding(new Insets(35));VBox.setVgrow(table,Priority.ALWAYS);return body;
    }

    private record DraftSaleLine(SalePresentation p,BigDecimal quantity,BigDecimal finalPrice,long totalCents){}
    private record DraftSalePay(String method,BigDecimal amount,String reference,long cents){}
    private void newSaleDialog(){
        try{
            Stage pop=popup("Nueva venta / caja");
            List<Customer> customers=customerService.all().stream().filter(Customer::active).toList();
            List<PriceList> lists=customerService.priceLists();
            if(lists.isEmpty())throw new IllegalStateException("No hay listas de precios activas");
            PriceList publicList=lists.stream().filter(l->"PUBLICO".equals(l.code())).findFirst().orElseThrow();
            ComboBox<Customer> customer=new ComboBox<>(FXCollections.observableArrayList(customers));
            customer.setPromptText("Consumidor final (sin ficha)");customer.setMaxWidth(Double.MAX_VALUE);
            ComboBox<PriceList> list=new ComboBox<>(FXCollections.observableArrayList(lists));
            list.setValue(publicList);list.setMaxWidth(Double.MAX_VALUE);list.setDisable(!session.isAdmin());
            ComboBox<String> document=new ComboBox<>(FXCollections.observableArrayList("BOLETA","FACTURA"));
            document.setValue("BOLETA");
            TextField filter=input("Buscar SKU, nombre o presentación...");
            ComboBox<SalePresentation> choice=new ComboBox<>();choice.setMaxWidth(Double.MAX_VALUE);
            TextField qty=input("Cantidad de presentaciones");qty.setText("1");
            TextField price=input("Precio unitario S/ (con IGV)");price.setEditable(session.isAdmin());
            Label available=muted("Selecciona una presentación con precio configurado.");
            var lines=FXCollections.<DraftSaleLine>observableArrayList();
            var payments=FXCollections.<DraftSalePay>observableArrayList();
            TableView<DraftSaleLine> lineTable=new TableView<>(lines);lineTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            lineTable.setPrefHeight(200);
            lineTable.getColumns().addAll(genericColumn("Producto",x->x.p().sku()+" · "+x.p().product()+" · "+x.p().presentation()),
                    genericColumn("Cantidad",x->x.quantity().stripTrailingZeros().toPlainString()),
                    genericColumn("Precio",x->"S/ "+x.finalPrice().setScale(2).toPlainString()),
                    genericColumn("Importe",x->soles(x.totalCents())));
            Label total=screenTitle("Total: S/ 0.00");
            Runnable recalc=()->{
                long cents=0;for(DraftSaleLine l:lines)cents=Math.addExact(cents,l.totalCents());
                total.setText("Total: "+soles(cents));
            };
            final List<SalePresentation>[] catalog=new List[]{List.of()};
            Runnable filterCatalog=()->{
                String q=filter.getText().trim().toLowerCase();
                choice.setItems(FXCollections.observableArrayList(catalog[0].stream()
                        .filter(p->p.toString().toLowerCase().contains(q)).limit(150).toList()));
                choice.setValue(null);
            };
            Runnable updateCatalog=()->{
                try{catalog[0]=saleService.sellablePresentations(list.getValue().id());filterCatalog.run();
                    lines.clear();payments.clear();recalc.run();
                }catch(Exception ex){showError("No se pudo cargar precios",ex);}
            };
            filter.textProperty().addListener((o,old,n)->filterCatalog.run());
            list.valueProperty().addListener((o,old,n)->{if(n!=null)updateCatalog.run();});
            customer.valueProperty().addListener((o,old,n)->{
                long chosen=n==null||n.priceListId()==null?publicList.id():n.priceListId();
                list.getItems().stream().filter(p->p.id()==chosen).findFirst().ifPresentOrElse(list::setValue,
                        ()->list.setValue(publicList));
                if(list.getValue()!=null&&list.getValue().id()==chosen)updateCatalog.run();
            });
            choice.valueProperty().addListener((o,old,p)->{
                if(p==null){available.setText("Selecciona una presentación con precio configurado.");price.clear();return;}
                available.setText("Stock: "+DecimalUnits.displayMilli(p.stockMilli())+" "+p.unitCode()+
                        " · Contenido: "+DecimalUnits.displayMilli(p.factorMilli())+" "+p.unitCode());
                price.setText(BigDecimal.valueOf(p.listPriceMicro(),4).setScale(2).toPlainString());
            });
            updateCatalog.run();
            Button addLine=secondary("Añadir producto"),remove=secondary("Quitar producto");
            addLine.setOnAction(e->{try{
                SalePresentation p=choice.getValue();if(p==null)throw new IllegalArgumentException("Selecciona un producto");
                BigDecimal q=new BigDecimal(qty.getText().trim()),v=new BigDecimal(price.getText().trim());
                long qtyMilli=DecimalUnits.toMilli(q,p.factorMilli()==1000?p.unitDecimals():0);
                long finalMicro=DecimalUnits.toPriceMicro(v);
                if(qtyMilli<=0||finalMicro<=0||finalMicro>p.listPriceMicro())throw new IllegalArgumentException("Cantidad o precio inválido");
                if(!session.isAdmin()&&finalMicro!=p.listPriceMicro())throw new SecurityException("Descuentos reservados al administrador");
                if(lines.stream().anyMatch(l->l.p().id()==p.id()))throw new IllegalArgumentException("Presentación repetida; quita y vuelve a agregar");
                lines.add(new DraftSaleLine(p,q,v,SaleService.grossCents(qtyMilli,finalMicro)));
                recalc.run();choice.setValue(null);filter.clear();qty.setText("1");
            }catch(Exception ex){showError("No se pudo añadir el producto",ex);}});
            remove.setOnAction(e->{DraftSaleLine row=lineTable.getSelectionModel().getSelectedItem();
                if(row!=null){lines.remove(row);recalc.run();}});
            ComboBox<String> method=new ComboBox<>(FXCollections.observableArrayList("EFECTIVO","YAPE","PLIN","TARJETA","TRANSFERENCIA","OTRO"));
            method.setValue("EFECTIVO");
            TextField amount=input("Monto S/"),reference=input("N.° de operación (opcional)");
            TableView<DraftSalePay> payTable=new TableView<>(payments);payTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            payTable.setPrefHeight(130);
            payTable.getColumns().addAll(genericColumn("Medio",DraftSalePay::method),
                    genericColumn("Monto",p->soles(p.cents())),
                    genericColumn("Referencia",p->p.reference()==null?"—":p.reference()));
            Label paid=muted("Pagado: S/ 0.00");
            Runnable recalcPaid=()->{long n=0;for(DraftSalePay p:payments)n+=p.cents();paid.setText("Pagado: "+soles(n));};
            Button addPay=secondary("Agregar pago"),removePay=secondary("Quitar pago");
            addPay.setOnAction(e->{try{
                BigDecimal value=new BigDecimal(amount.getText().trim());long cents=SaleService.toCents(value);
                if(cents<=0)throw new IllegalArgumentException("El pago debe ser mayor que cero");
                payments.add(new DraftSalePay(method.getValue(),value,reference.getText().trim(),cents));
                amount.clear();reference.clear();recalcPaid.run();
            }catch(Exception ex){showError("Pago inválido",ex);}});
            removePay.setOnAction(e->{DraftSalePay row=payTable.getSelectionModel().getSelectedItem();
                if(row!=null){payments.remove(row);recalcPaid.run();}});
            // UUID generado una sola vez para esta operación, incluyendo reintentos tras errores de conexión/BD.
            String operationId=java.util.UUID.randomUUID().toString();
            Button confirmSale=primary("Confirmar venta y descontar stock"),cancel=secondary("Cancelar");
            cancel.setOnAction(e->pop.close());
            confirmSale.setOnAction(e->{try{
                if(lines.isEmpty())throw new IllegalArgumentException("Añade al menos un producto");
                if(payments.isEmpty())throw new IllegalArgumentException("Registra el pago");
                if(!confirm("¿Registrar venta por "+total.getText()+"? Se descontará el inventario. " +
                        "El comprobante NO será emitido ante SUNAT en esta versión."))return;
                List<SaleLineInput> inputLines=new ArrayList<>();
                for(DraftSaleLine l:lines)inputLines.add(new SaleLineInput(l.p().id(),l.quantity(),l.finalPrice()));
                List<SalePaymentInput> inputPays=new ArrayList<>();
                for(DraftSalePay p:payments)inputPays.add(new SalePaymentInput(p.method(),p.amount(),p.reference()));
                SaleResult r=saleService.confirm(new NewSale(operationId,
                        customer.getValue()==null?null:customer.getValue().id(),list.getValue().id(),
                        document.getValue(),inputLines,inputPays),session);
                pop.close();showWorkspace("ventas");
                warn("Venta #"+r.id()+" registrada ("+soles(r.totalCents())+"). Kardex actualizado.\n"+
                        "COMPROBANTE "+r.documentType()+": PENDIENTE; no es un comprobante fiscal emitido."+
                        (r.repeated()?"\nSolicitud repetida identificada, sin descuento adicional.":""));
            }catch(Exception ex){showError("No se pudo confirmar venta",ex);}});
            VBox top=new VBox(10,screenTitle("Nueva venta"),
                    muted("Precios incluyen IGV referencial 18%; no emitimos boletas/facturas todavía."),
                    labeled("Cliente (opcional para consumidor final)",customer),labeled("Lista de precios",list),
                    labeled("Comprobante solicitado",document));
            HBox inputs=new HBox(10,labeled("Cantidad",qty),labeled("Precio (S/)",price),addLine);
            inputs.setAlignment(Pos.BOTTOM_LEFT);
            HBox payInput=new HBox(10,labeled("Medio",method),labeled("Monto",amount),labeled("Referencia",reference),addPay);
            payInput.setAlignment(Pos.BOTTOM_LEFT);
            VBox body=new VBox(12,top,labeled("Buscar producto",filter),labeled("Presentación",choice),available,
                    inputs,lineTable,new HBox(12,remove,total),new Separator(),screenTitle("Pagos combinados"),
                    payInput,payTable,new HBox(12,removePay,paid),new HBox(12,cancel,confirmSale));
            body.setPadding(new Insets(25));ScrollPane sc=new ScrollPane(body);sc.setFitToWidth(true);
            pop.setScene(styled(new StackPane(sc),1150,850));pop.showAndWait();
        }catch(Exception ex){showError("No se pudo abrir una nueva venta",ex);}
    }

    private void saleDetailsDialog(SaleEntry sale){
        try{
            Stage pop=popup("Venta #"+sale.id());
            TableView<SaleDetailEntry> products=new TableView<>();products.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            products.getColumns().addAll(genericColumn("SKU",SaleDetailEntry::sku),
                    genericColumn("Producto",SaleDetailEntry::description),
                    genericColumn("Cantidad",r->DecimalUnits.displayMilli(r.quantityPresentationMilli())),
                    genericColumn("Precio final",r->DecimalUnits.displayPrice(r.finalUnitMicro())),
                    genericColumn("IGV",r->soles(r.taxCents())),
                    genericColumn("Total",r->soles(r.totalCents())));
            products.setItems(FXCollections.observableArrayList(saleService.details(sale.id())));
            TableView<SalePaymentEntry> paid=new TableView<>();paid.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            paid.getColumns().addAll(genericColumn("Medio",SalePaymentEntry::method),
                    genericColumn("Importe",p->soles(p.cents())),
                    genericColumn("Referencia",p->p.reference()==null?"—":p.reference()));
            paid.setItems(FXCollections.observableArrayList(saleService.payments(sale.id())));paid.setPrefHeight(140);
            Button close=secondary("Cerrar");close.setOnAction(e->pop.close());
            VBox body=new VBox(13,screenTitle("Venta #"+sale.id()),
                    muted(sale.date()+" · "+sale.customer()+" · "+sale.documentType()+" "+sale.documentStatus()),
                    products,screenTitle("Pagos"),paid,screenTitle("Total: "+soles(sale.totalCents())),
                    muted("Importante: comprobante pendiente de facturación electrónica."),close);
            VBox.setVgrow(products,Priority.ALWAYS);body.setPadding(new Insets(25));
            pop.setScene(styled(body,1080,760));pop.showAndWait();
        }catch(Exception ex){showError("No se pudo cargar detalle de venta",ex);}
    }

    private Node usersPage() {
        Label title = screenTitle("Trabajadores");
        Label note = muted("Cada persona debe tener una cuenta propia. Solo el administrador registra usuarios nuevos.");
        if (!session.isAdmin()) return new VBox(title,note);
        Button add = primary("+  Registrar vendedor");
        add.setOnAction(e -> newWorkerDialog());
        Label hint = muted("En esta etapa se crean cuentas con rol VENDEDOR. Cambios de contraseña, bloqueo y permisos granulares llegarán después.");
        hint.setWrapText(true);
        VBox info = new VBox(14,note,add,hint); info.getStyleClass().add("information-panel");
        VBox content = new VBox(24,title,info); content.setPadding(new Insets(36));
        return content;
    }

    private void newProductDialog() {
        try {
            Stage popup = popup("Nuevo producto");
            TextField sku = input("Ej.: FIL-BOS-001");
            TextField name = input("Nombre del repuesto");
            ComboBox<CatalogItem> category = new ComboBox<>(FXCollections.observableArrayList(catalogs.categories()));
            ComboBox<CatalogItem> brand = new ComboBox<>(FXCollections.observableArrayList(catalogs.brands()));
            ComboBox<CatalogItem> unit = new ComboBox<>(FXCollections.observableArrayList(catalogs.units()));
            category.setMaxWidth(Double.MAX_VALUE); unit.setMaxWidth(Double.MAX_VALUE);brand.setMaxWidth(Double.MAX_VALUE);
            if (!category.getItems().isEmpty()) category.getSelectionModel().selectFirst();
            if (!unit.getItems().isEmpty()) unit.getSelectionModel().selectFirst();
            TextField price = input("0.00");
            TextField initial = input("0"); initial.setText("0");
            TextField min = input("0"); min.setText("0");
            TextField country=input("País de origen (opcional)");
            TextField model=input("Modelo o referencia (opcional)");
            TextArea notes=new TextArea();notes.setPromptText("Observaciones (opcional)");notes.setPrefRowCount(2);
            Label instruction = muted("El stock inicial solo puede ser declarado por un administrador. No se edita el saldo directamente.");
            instruction.setWrapText(true);
            if (!session.isAdmin()) { initial.setDisable(true); initial.setText("0"); }
            Button save = primary("Guardar producto");
            Button cancel = secondary("Cancelar"); cancel.setOnAction(e->popup.close());
            save.setOnAction(e -> {
                try {
                    if (category.getValue()==null || unit.getValue()==null) throw new IllegalArgumentException("Selecciona categoría y unidad");
                    NewProduct newProduct = new NewProduct(sku.getText(),name.getText(),category.getValue().id(),
                            unit.getValue().id(),new BigDecimal(initial.getText().trim()),
                            new BigDecimal(min.getText().trim()),new BigDecimal(price.getText().trim()),
                            brand.getValue()==null?null:brand.getValue().id(),country.getText(),model.getText(),notes.getText());
                    products.register(newProduct,session);
                    popup.close(); showWorkspace("productos");
                } catch (Exception ex) { showError("No se pudo guardar el producto",ex); }
            });
            HBox controls = new HBox(12,cancel,save); controls.setAlignment(Pos.CENTER_RIGHT);
            VBox form = new VBox(12,screenTitle("Agregar producto"),
                    labeled("SKU *",sku),labeled("Nombre *",name),labeled("Categoría *",category),
                    labeled("Marca (opcional)",brand),labeled("País",country),labeled("Modelo",model),
                    labeled("Unidad base *",unit),labeled("Precio público en S/ *",price),
                    labeled("Stock inicial",initial),labeled("Stock mínimo",min),labeled("Notas",notes),instruction,controls);
            form.setPadding(new Insets(25));
            ScrollPane scroller = new ScrollPane(form); scroller.setFitToWidth(true);
            popup.setScene(styled(new StackPane(scroller),570,740)); popup.showAndWait();
        } catch (Exception ex) { showError("No se pudo abrir el formulario",ex); }
    }

    private void metadataDialog(Product selected) {
        try {
            ProductDetails d=productCatalogService.details(selected.id());
            Stage popup=popup("Ficha del producto: "+d.sku());
            ComboBox<CatalogItem> brand=new ComboBox<>(FXCollections.observableArrayList(catalogs.brands()));
            brand.setPromptText("Sin marca"); brand.setMaxWidth(Double.MAX_VALUE);
            for(CatalogItem item:brand.getItems())if(d.brandId()!=null && item.id()==d.brandId())brand.setValue(item);
            Button clearBrand=secondary("Quitar marca");clearBrand.setOnAction(e->brand.setValue(null));
            TextField country=input("País de procedencia");country.setText(d.country()==null?"":d.country());
            TextField model=input("Modelo o referencia");model.setText(d.model()==null?"":d.model());
            TextArea notes=new TextArea(d.observations()==null?"":d.observations());notes.setPrefRowCount(3);
            Button save=primary("Guardar cambios");Button cancel=secondary("Cerrar");cancel.setOnAction(e->popup.close());
            save.setOnAction(e->{
                try {
                    productCatalogService.editMetadata(d.id(),brand.getValue()==null?null:brand.getValue().id(),
                            country.getText(),model.getText(),notes.getText(),session);
                    popup.close();showWorkspace("productos");
                }catch(Exception ex){showError("No se pudo actualizar la ficha",ex);}
            });
            HBox actions=new HBox(10,cancel,save);actions.setAlignment(Pos.CENTER_RIGHT);
            VBox body=new VBox(13,screenTitle("Ficha de "+d.name()),muted("SKU: "+d.sku()+"  ·  Unidad base: "+d.unitCode()),
                    labeled("Marca",new HBox(8,brand,clearBrand)),labeled("País de origen",country),
                    labeled("Modelo / referencia",model),labeled("Observaciones",notes),actions);
            body.setPadding(new Insets(24));popup.setScene(styled(body,580,560));popup.showAndWait();
        }catch(Exception ex){showError("No se pudo abrir la ficha",ex);}
    }

    private void compatibilitiesDialog(Product selected) {
        try {
            Stage popup=popup("Compatibilidades: "+selected.sku());
            TableView<ProductCompatibility> table=new TableView<>();table.setPrefHeight(270);
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            table.getColumns().addAll(genericColumn("Compatibilidad",ProductCompatibility::description),
                    genericColumn("Estado",p->p.active()?"Activa":"Inactiva"));
            Runnable load=()->{
                try{table.setItems(FXCollections.observableArrayList(productCatalogService.compatibilities(selected.id())));}
                catch(Exception ex){showError("No se pudo listar compatibilidades",ex);}
            };
            TextField compat=input("Ej.: Toyota Hilux 2.8 2016-2024");
            Button add=primary("+ Agregar");
            add.setOnAction(e->{
                try {productCatalogService.addCompatibility(selected.id(),compat.getText(),session);compat.clear();load.run();}
                catch(Exception ex){showError("No se pudo agregar la compatibilidad",ex);}
            });
            Button disable=secondary("Activar / Desactivar");disable.setDisable(true);
            table.getSelectionModel().selectedItemProperty().addListener((obs,oldValue,value)->
                    disable.setDisable(value==null));
            disable.setOnAction(e->{
                ProductCompatibility p=table.getSelectionModel().getSelectedItem();
                if(p!=null && confirm("¿"+(p.active()?"Desactivar":"Activar")+" la compatibilidad '"+p.description()+"'?")) {
                    try{productCatalogService.setCompatibilityActive(selected.id(),p.id(),!p.active(),session);load.run();}
                    catch(Exception ex){showError("No se pudo cambiar el estado",ex);}
                }
            });
            Button done=secondary("Cerrar");done.setOnAction(e->popup.close());
            HBox entry=new HBox(10,compat,add);HBox.setHgrow(compat,Priority.ALWAYS);
            VBox body=new VBox(14,screenTitle("Compatibilidades de "+selected.name()),
                    muted("Referencias y vehículos compatibles. Puedes registrar varias por producto."),
                    table,entry,new HBox(12,disable,done));body.setPadding(new Insets(24));
            load.run();popup.setScene(styled(body,710,560));popup.showAndWait();
        }catch(Exception ex){showError("No se pudo abrir compatibilidades",ex);}
    }

    private void presentationsDialog(Product selected) {
        try {
            ProductDetails detail=productCatalogService.details(selected.id());
            Stage popup=popup("Presentaciones: "+selected.sku());
            TableView<ProductPresentation> table=new TableView<>();table.setPrefHeight(240);
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            table.getColumns().addAll(
                    genericColumn("Presentación",ProductPresentation::name),
                    genericColumn("Equivale a",p->DecimalUnits.displayMilli(p.factorMilli())+" "+detail.unitCode()),
                    genericColumn("Compra",p->p.buyEnabled()?"Sí":"No"),
                    genericColumn("Venta",p->p.sellEnabled()?"Sí":"No"),
                    genericColumn("Precio público",p->DecimalUnits.displayPrice(p.publicPriceMicro())),
                    genericColumn("Estado",p->p.active()?"Activa":"Inactiva"));
            Runnable load=()->{
                try{table.setItems(FXCollections.observableArrayList(productCatalogService.presentations(selected.id())));}
                catch(Exception ex){showError("No se pudieron consultar las presentaciones",ex);}
            };
            TextField name=input("Ej.: Caja x 12");TextField factor=input("Ej.: 12");
            TextField barcode=input("Código de barras (opcional)");TextField price=input("S/ (si se vende)");
            CheckBox buy=new CheckBox("Se compra");buy.setSelected(true);
            CheckBox sell=new CheckBox("Se vende");sell.setSelected(true);
            Button add=primary("+ Agregar presentación");
            add.setOnAction(e->{
                try {
                    BigDecimal amount=new BigDecimal(factor.getText().trim());
                    BigDecimal amountPrice=price.getText().isBlank()?null:new BigDecimal(price.getText().trim());
                    productCatalogService.addPresentation(selected.id(),name.getText(),amount,barcode.getText(),
                            buy.isSelected(),sell.isSelected(),amountPrice,session);
                    name.clear();factor.clear();barcode.clear();price.clear();load.run();
                }catch(Exception ex){showError("No se pudo registrar la presentación",ex);}
            });
            Button disable=secondary("Activar / Desactivar");disable.setDisable(true);
            table.getSelectionModel().selectedItemProperty().addListener((obs,oldValue,value)->
                    disable.setDisable(value==null || value.factorMilli()==1000));
            disable.setOnAction(e->{
                ProductPresentation p=table.getSelectionModel().getSelectedItem();
                if(p!=null && confirm("¿"+(p.active()?"Desactivar":"Activar")+" la presentación '"+p.name()+"'?")) {
                    try{productCatalogService.setPresentationActive(selected.id(),p.id(),!p.active(),session);load.run();}
                    catch(Exception ex){showError("No se pudo cambiar el estado",ex);}
                }
            });
            Button close=secondary("Cerrar");close.setOnAction(e->popup.close());
            HBox opts=new HBox(14,buy,sell);opts.setAlignment(Pos.CENTER_LEFT);
            Label help=muted("Factor = unidades base que contiene esta presentación. Ej.: Caja x 12 = 12 piezas. "+
                    "El registro NO aumenta ni descuenta stock; eso ocurrirá al registrar compras o ventas.");help.setWrapText(true);
            VBox form=new VBox(10,labeled("Nombre",name),labeled("Factor en "+detail.unitCode(),factor),
                    labeled("Código de barras",barcode),labeled("Precio de venta (S/)",price),opts,add);
            VBox body=new VBox(12,screenTitle("Presentaciones de "+selected.name()),help,table,form,
                    new HBox(12,disable,close));body.setPadding(new Insets(24));
            ScrollPane scroller=new ScrollPane(body);scroller.setFitToWidth(true);
            load.run();popup.setScene(styled(new StackPane(scroller),850,780));popup.showAndWait();
        }catch(Exception ex){showError("No se pudo abrir presentaciones",ex);}
    }

    private void editProductDialog(Product selected) {
        try {
            ProductEdit p=maintenance.product(selected.id());
            Stage pop=popup("Editar producto: "+p.sku());
            TextField name=input("Nombre");name.setText(p.name());
            ComboBox<CatalogItem> category=new ComboBox<>(FXCollections.observableArrayList(catalogs.categories()));
            for(CatalogItem entry:category.getItems())if(entry.id()==p.categoryId())category.setValue(entry);
            ComboBox<CatalogItem> brand=new ComboBox<>(FXCollections.observableArrayList(catalogs.brands()));
            for(CatalogItem entry:brand.getItems())if(p.brandId()!=null && entry.id()==p.brandId())brand.setValue(entry);
            Button clear=secondary("Sin marca");clear.setOnAction(e->brand.setValue(null));
            TextField country=input("País (opcional)");country.setText(p.country()==null?"":p.country());
            TextField model=input("Referencia (opcional)");model.setText(p.model()==null?"":p.model());
            TextArea notes=new TextArea(p.notes()==null?"":p.notes());notes.setPrefRowCount(2);
            TextField min=input("Mínimo");min.setText(DecimalUnits.displayMilli(p.minimumMilli()));
            TextField max=input("Sin máximo");max.setText(p.maximumMilli()==null?"":DecimalUnits.displayMilli(p.maximumMilli()));
            Label info=muted("SKU "+p.sku()+" · Unidad "+p.unitCode()+" · Stock "+DecimalUnits.displayMilli(p.stockMilli())+
                ". El stock se modifica solamente mediante ajustes o movimientos.");info.setWrapText(true);
            Button save=primary("Guardar cambios");Button cancel=secondary("Cerrar");cancel.setOnAction(e->pop.close());
            save.setOnAction(e->{try {
                if(category.getValue()==null)throw new IllegalArgumentException("Selecciona la categoría");
                maintenance.updateProduct(p.id(),name.getText(),category.getValue().id(),
                   brand.getValue()==null?null:brand.getValue().id(),country.getText(),model.getText(),
                   notes.getText(),new BigDecimal(min.getText().trim()),
                   max.getText().isBlank()?null:new BigDecimal(max.getText().trim()),session);
                pop.close();showWorkspace("productos");
            }catch(Exception ex){showError("No se pudo editar el producto",ex);}});
            VBox content=new VBox(13,screenTitle("Editar "+p.sku()),info,labeled("Nombre",name),
               labeled("Categoría",category),labeled("Marca",new HBox(8,brand,clear)),
               labeled("País de origen",country),labeled("Modelo / referencia",model),
               labeled("Stock mínimo",min),labeled("Stock máximo (opcional)",max),
               labeled("Observaciones",notes),new HBox(12,cancel,save));
            content.setPadding(new Insets(24));
            ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);
            pop.setScene(styled(new StackPane(scroll),690,790));pop.showAndWait();
        }catch(Exception ex){showError("No se pudo abrir el editor",ex);}
    }

    private void pricesDialog(Product selected) {
        try {
            Stage pop=popup("Precios: "+selected.sku());
            var presentations=productCatalogService.presentations(selected.id()).stream()
                    .filter(p->p.active()).toList();
            ComboBox<ProductPresentation> presentation=new ComboBox<>(FXCollections.observableArrayList(presentations));
            presentation.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(ProductPresentation p){return p==null?"":p.name();}
                @Override public ProductPresentation fromString(String s){return null;}
            });
            if(!presentation.getItems().isEmpty())presentation.getSelectionModel().selectFirst();
            ComboBox<CatalogItem> lists=new ComboBox<>(FXCollections.observableArrayList(maintenance.priceLists()));
            if(!lists.getItems().isEmpty())lists.getSelectionModel().selectFirst();
            TextField amount=input("Precio (S/)");
            TextField why=input("Motivo obligatorio (ej.: variación del costo)");
            TableView<PriceHistoryEntry> table=new TableView<>();table.setPrefHeight(250);
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            table.getColumns().addAll(genericColumn("Presentación",PriceHistoryEntry::presentation),
                genericColumn("Lista",PriceHistoryEntry::priceList),
                genericColumn("Precio",p->DecimalUnits.displayPrice(p.priceMicro())),
                genericColumn("Inicio",PriceHistoryEntry::from),
                genericColumn("Fin",p->p.until()==null?"Vigente":p.until()),
                genericColumn("Usuario",PriceHistoryEntry::author),
                genericColumn("Motivo",PriceHistoryEntry::reason));
            Runnable load=()->{try{table.setItems(FXCollections.observableArrayList(maintenance.priceHistory(selected.id())));}
                catch(Exception ex){showError("No se pudo cargar precios",ex);}};
            Button save=primary("Guardar nuevo precio");save.setDisable(!session.isAdmin());
            save.setOnAction(e->{try{
                if(presentation.getValue()==null || lists.getValue()==null)throw new IllegalArgumentException("Elige presentación y lista");
                maintenance.changePrice(presentation.getValue().id(),lists.getValue().id(),
                    new BigDecimal(amount.getText().trim()),why.getText(),session);
                amount.clear();why.clear();load.run();showWorkspace("productos");
            }catch(Exception ex){showError("No se pudo cambiar el precio",ex);}});
            Button done=secondary("Cerrar");done.setOnAction(e->pop.close());
            Label tip=muted("Cada cambio cierra el precio anterior y crea una versión nueva. Los importes de ventas históricas no cambiarán.");
            tip.setWrapText(true);
            VBox content=new VBox(13,screenTitle("Precios por presentación"),tip,
               labeled("Presentación",presentation),labeled("Lista de precios",lists),
               labeled("Nuevo precio en soles",amount),labeled("Justificación",why),
               new HBox(10,save,done),table);
            content.setPadding(new Insets(24));pop.setScene(styled(content,980,700));
            load.run();pop.showAndWait();
        }catch(Exception ex){showError("No se pudo abrir precios",ex);}
    }

    private void adjustStockDialog(Product selected) {
        try {
            ProductEdit p=maintenance.product(selected.id());
            Stage pop=popup("Ajustar inventario: "+p.sku());
            Label stock=screenTitle("Stock actual: "+DecimalUnits.displayMilli(p.stockMilli())+" "+p.unitCode());
            ComboBox<String> mode=new ComboBox<>(FXCollections.observableArrayList("Entrada (+)","Salida (-)"));
            mode.getSelectionModel().selectFirst();
            TextField quantity=input("Cantidad positiva, ej.: 2");
            TextArea reason=new TextArea();reason.setPromptText("Motivo real del ajuste, ej.: diferencia de conteo físico");
            reason.setPrefRowCount(3);
            Label note=muted("Solo administradores. No se admite stock negativo. Un ajuste genera un movimiento inmutable.");
            note.setWrapText(true);
            Button save=primary("Registrar ajuste");Button cancel=secondary("Cancelar");cancel.setOnAction(e->pop.close());
            save.setOnAction(e->{try{
                BigDecimal q=new BigDecimal(quantity.getText().trim());
                if(q.signum()<=0)throw new IllegalArgumentException("Escribe una cantidad mayor a cero");
                if("Salida (-)".equals(mode.getValue()))q=q.negate();
                if(!confirm("¿Registrar ajuste de "+q.toPlainString()+" "+p.unitCode()+"? Esta operación quedará en el kardex."))return;
                maintenance.adjustStock(p.id(),q,reason.getText(),session);
                pop.close();showWorkspace("productos");
            }catch(Exception ex){showError("No se pudo registrar el ajuste",ex);}});
            VBox body=new VBox(14,screenTitle("Ajuste autorizado"),stock,note,labeled("Operación",mode),
                labeled("Cantidad",quantity),labeled("Motivo obligatorio",reason),new HBox(12,cancel,save));
            body.setPadding(new Insets(24));pop.setScene(styled(body,630,530));pop.showAndWait();
        }catch(Exception ex){showError("No se pudo abrir ajuste",ex);}
    }

    private void historyDialog(Product selected) {
        try {
            Stage pop=popup("Historial de: "+selected.sku());
            TabPane tabs=new TabPane();
            TableView<StockHistoryEntry> kardex=new TableView<>();
            kardex.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            kardex.getColumns().addAll(genericColumn("Fecha",StockHistoryEntry::date),
                genericColumn("Tipo",StockHistoryEntry::type),
                genericColumn("Variación",m->DecimalUnits.displayMilli(m.deltaMilli())),
                genericColumn("Saldo posterior",m->DecimalUnits.displayMilli(m.accumulatedMilli())),
                genericColumn("Usuario",StockHistoryEntry::author),
                genericColumn("Motivo",StockHistoryEntry::reason));
            kardex.setItems(FXCollections.observableArrayList(maintenance.stockHistory(selected.id())));
            TableView<PriceHistoryEntry> prices=new TableView<>();
            prices.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            prices.getColumns().addAll(genericColumn("Presentación",PriceHistoryEntry::presentation),
                genericColumn("Lista",PriceHistoryEntry::priceList),
                genericColumn("Precio",p->DecimalUnits.displayPrice(p.priceMicro())),
                genericColumn("Desde",PriceHistoryEntry::from),
                genericColumn("Hasta",p->p.until()==null?"Vigente":p.until()),
                genericColumn("Responsable",PriceHistoryEntry::author));
            prices.setItems(FXCollections.observableArrayList(maintenance.priceHistory(selected.id())));
            Tab a=new Tab("Movimientos / Kardex",kardex),b=new Tab("Historial de precios",prices);
            a.setClosable(false);b.setClosable(false);tabs.getTabs().addAll(a,b);
            Button close=secondary("Cerrar");close.setOnAction(e->pop.close());
            VBox box=new VBox(14,screenTitle("Historial de "+selected.name()),
                    muted("Registros de auditoría comercial; no se eliminan ni se editan."),tabs,close);
            VBox.setVgrow(tabs,Priority.ALWAYS);box.setPadding(new Insets(24));
            pop.setScene(styled(box,1000,630));pop.showAndWait();
        }catch(Exception ex){showError("No se pudo abrir historial",ex);}
    }

    private boolean confirm(String msg) {
        Alert alert=new Alert(Alert.AlertType.CONFIRMATION,msg,ButtonType.YES,ButtonType.NO);
        return alert.showAndWait().orElse(ButtonType.NO)==ButtonType.YES;
    }

    private <T> TableColumn<T,String> genericColumn(String label,java.util.function.Function<T,String> extract) {
        TableColumn<T,String> col=new TableColumn<>(label);
        col.setCellValueFactory(row->new ReadOnlyStringWrapper(extract.apply(row.getValue())));
        return col;
    }

    private void newWorkerDialog() {
        Stage popup = popup("Registrar vendedor");
        TextField name = input("Nombre completo");
        TextField login = input("Usuario");
        PasswordField pwd = password("12 caracteres mínimo");
        PasswordField confirm = password("Repetir contraseña");
        Button save = primary("Guardar trabajador");
        Button cancel = secondary("Cancelar"); cancel.setOnAction(e->popup.close());
        save.setOnAction(e -> {
            if (!pwd.getText().equals(confirm.getText())) { warn("Las contraseñas no coinciden"); return; }
            char[] pass = pwd.getText().toCharArray();
            try { users.createWorker(name.getText(),login.getText(),pass,session);
                popup.close(); warn("Trabajador creado. Ya puede iniciar sesión.");
            } catch (Exception ex) { showError("No se pudo crear el trabajador",ex); }
            finally { Arrays.fill(pass,'\0'); pwd.clear(); confirm.clear(); }
        });
        HBox controls = new HBox(12,cancel,save); controls.setAlignment(Pos.CENTER_RIGHT);
        VBox content = new VBox(14,screenTitle("Nuevo vendedor"),labeled("Nombre",name),
                labeled("Usuario",login),labeled("Contraseña",pwd),labeled("Confirmar",confirm),controls);
        content.setPadding(new Insets(28));
        popup.setScene(styled(content,490,480)); popup.showAndWait();
    }

    private Stage popup(String title) {
        Stage stage = new Stage(); stage.setTitle(title); stage.initOwner(mainWindow);
        stage.initModality(Modality.WINDOW_MODAL); return stage;
    }
    private Scene styled(Pane pane, int width, int height) {
        Scene scene = new Scene(pane,width,height);
        scene.getStylesheets().add(getClass().getResource("/ui/theme.css").toExternalForm());
        return scene;
    }
    private TableColumn<Product,String> column(String label, java.util.function.Function<Product,String> extract) {
        TableColumn<Product,String> col = new TableColumn<>(label);
        col.setCellValueFactory(row -> new ReadOnlyStringWrapper(extract.apply(row.getValue())));
        return col;
    }
    private VBox summaryCard(String label,String value,String foot) {
        Label num = new Label(value); num.getStyleClass().add("card-number");
        VBox card = new VBox(10,muted(label),num,muted(foot)); card.getStyleClass().add("summary-card");
        card.setMaxWidth(Double.MAX_VALUE); return card;
    }
    private Button navButton(String title,boolean active,Runnable command) {
        Button button = new Button(title); button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("nav-button");
        if (active) button.getStyleClass().add("nav-active");
        button.setOnAction(e->command.run()); return button;
    }
    private VBox labeled(String title,Node content) { return new VBox(6,muted(title),content); }
    private Label muted(String text) { Label l = new Label(text == null?"":text); l.getStyleClass().add("muted"); return l; }
    private Label overline(String text) { Label l = new Label(text); l.getStyleClass().add("overline"); return l; }
    private Label screenTitle(String title) { Label l = new Label(title); l.getStyleClass().add("screen-title"); return l; }
    private TextField input(String placeholder) { TextField t = new TextField(); t.setPromptText(placeholder); return t; }
    private PasswordField password(String placeholder) { PasswordField p = new PasswordField(); p.setPromptText(placeholder); return p; }
    private Button primary(String title) { Button b = new Button(title); b.getStyleClass().add("primary-button"); return b; }
    private Button secondary(String title) { Button b = new Button(title); b.getStyleClass().add("secondary-button"); return b; }
    private void warn(String msg) { new Alert(Alert.AlertType.INFORMATION,msg,ButtonType.OK).showAndWait(); }
    private void showError(String title,Exception e) {
        Throwable root = e;
        while (root.getCause()!=null && root.getCause()!=root) root = root.getCause();
        String detail = root.getMessage();
        if (detail == null) detail = root.toString();
        if (detail.contains("UNIQUE constraint failed: producto.sku")) detail = "Ya existe un producto con ese SKU";
        if (detail.contains("UNIQUE constraint failed: usuario.login")) detail = "Ese usuario ya está registrado";
        Alert alert = new Alert(Alert.AlertType.ERROR,title+"\n"+detail,ButtonType.OK);
        alert.setHeaderText("No se pudo completar la operación"); alert.showAndWait();
    }
}
