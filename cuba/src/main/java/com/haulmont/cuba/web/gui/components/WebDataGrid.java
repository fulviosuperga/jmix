/*
 * Copyright 2020 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.haulmont.cuba.web.gui.components;

import com.haulmont.cuba.core.global.View;
import com.haulmont.cuba.gui.components.DataGrid;
import com.haulmont.cuba.gui.components.LookupComponent;
import com.haulmont.cuba.gui.components.RowsCount;
import com.haulmont.cuba.gui.components.data.datagrid.AggregatableDataGridItems;
import com.haulmont.cuba.gui.components.data.meta.DatasourceDataUnit;
import com.haulmont.cuba.gui.components.valueprovider.DataGridConverterBasedValueProvider;
import com.haulmont.cuba.gui.data.CollectionDatasource;
import com.haulmont.cuba.gui.data.Datasource;
import com.haulmont.cuba.gui.data.DsBuilder;
import com.haulmont.cuba.gui.data.impl.DatasourceImplementation;
import com.haulmont.cuba.settings.binder.CubaDataGridSettingsBinder;
import com.haulmont.cuba.settings.component.LegacySettingsDelegate;
import com.haulmont.cuba.settings.converter.LegacyDataGridSettingsConverter;
import com.haulmont.cuba.web.gui.components.datagrid.DataGridDelegate;
import com.vaadin.data.ValueProvider;
import com.vaadin.ui.Grid;
import io.jmix.core.DevelopmentException;
import io.jmix.core.Entity;
import io.jmix.core.common.util.Preconditions;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.MetaPropertyPath;
import io.jmix.datatoolsui.accesscontext.UiShowEntityInfoContext;
import io.jmix.datatoolsui.action.ShowEntityInfoAction;
import io.jmix.ui.component.AggregationInfo;
import io.jmix.ui.component.ContentMode;
import io.jmix.ui.component.DataGridEditorFieldFactory;
import io.jmix.ui.component.Field;
import io.jmix.ui.component.data.BindingState;
import io.jmix.ui.component.data.DataGridItems;
import io.jmix.ui.component.data.datagrid.ContainerDataGridItems;
import io.jmix.ui.component.data.meta.EntityDataGridItems;
import io.jmix.ui.component.formatter.CollectionFormatter;
import io.jmix.ui.component.formatter.Formatter;
import io.jmix.ui.component.impl.AbstractDataGrid;
import io.jmix.ui.component.impl.DataGridImpl;
import io.jmix.ui.component.valueprovider.FormatterBasedValueProvider;
import io.jmix.ui.component.valueprovider.StringPresentationValueProvider;
import io.jmix.ui.component.valueprovider.YesNoIconPresentationValueProvider;
import io.jmix.ui.settings.component.binder.ComponentSettingsBinder;
import io.jmix.ui.widget.JmixGridEditorFieldFactory;
import org.dom4j.Element;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.jmix.core.common.util.Preconditions.checkNotNullArgument;

@Deprecated
public class WebDataGrid<E extends Entity> extends DataGridImpl<E>
        implements DataGrid<E>, LookupComponent.LookupSelectionChangeNotifier<E> {

    protected LegacySettingsDelegate settingsDelegate;
    protected DataGridDelegate dataGridDelegate;

    protected List<CellStyleProvider<? super E>> cellStyleProviders;
    protected CellDescriptionProvider<? super E> cellDescriptionProvider;

    @Override
    public void afterPropertiesSet() throws Exception {
        super.afterPropertiesSet();

        settingsDelegate = createSettingsDelegate();
        dataGridDelegate = createDataGridDelegate();
    }

    @Override
    public void applyDataLoadingSettings(Element element) {
        settingsDelegate.applyDataLoadingSettings(element);
    }

    @Override
    public void applySettings(Element element) {
        settingsDelegate.applySettings(element);
    }

    @Override
    public boolean saveSettings(Element element) {
        return settingsDelegate.saveSettings(element);
    }

    @Override
    public boolean isSettingsEnabled() {
        return settingsDelegate.isSettingsEnabled();
    }

    @Override
    public void setSettingsEnabled(boolean settingsEnabled) {
        settingsDelegate.setSettingsEnabled(settingsEnabled);
    }

    protected ComponentSettingsBinder getSettingsBinder() {
        return (ComponentSettingsBinder) this.applicationContext.getBean(CubaDataGridSettingsBinder.NAME);
    }

    @Override
    protected AbstractDataGrid.ColumnImpl<E> createColumn(String id, @Nullable MetaPropertyPath propertyPath, AbstractDataGrid<?, E> owner) {
        return new ColumnImpl<>(id, propertyPath, owner);
    }

    @Override
    public io.jmix.ui.component.DataGrid.Column<E> addGeneratedColumn(String columnId, ColumnGenerator<E, ?> generator) {
        return addGeneratedColumn(columnId, generator, columnsOrder.size());
    }

    @Override
    public io.jmix.ui.component.DataGrid.Column<E> addGeneratedColumn(String columnId, ColumnGenerator<E, ?> generator, int index) {
        Preconditions.checkNotNullArgument(columnId, "columnId is null");
        Preconditions.checkNotNullArgument(generator, "generator is null for column id '%s'", columnId);

        Function<ColumnGeneratorEvent<E>, ?> generatorFunction = (Function<ColumnGeneratorEvent<E>, Object>) columnGeneratorEvent ->
                generator.getValue(columnGeneratorEvent);

        io.jmix.ui.component.DataGrid.Column<E> existingColumn = getColumn(columnId);
        if (existingColumn != null) {
            index = columnsOrder.indexOf(existingColumn);
            removeColumn(existingColumn);
        }

        Grid.Column<E, Object> generatedColumn =
                component.addColumn(createGeneratedColumnValueProvider(columnId, generatorFunction));

        // Pass propertyPath from the existing column to support sorting
        ColumnImpl<E> column = new ColumnImpl<>(columnId,
                existingColumn != null ? existingColumn.getPropertyPath() : null,
                generator.getType(), this);
        if (existingColumn != null) {
            copyColumnProperties(column, existingColumn);
        } else {
            column.setCaption(columnId);
        }
        column.setGenerated(true);

        columns.put(column.getId(), column);
        columnsOrder.add(index, column);
        columnGenerators.put(column.getId(), generatorFunction);

        setupGridColumnProperties(generatedColumn, column);

        component.setColumnOrder(getColumnOrder());

        return column;
    }

    @Override
    public io.jmix.ui.component.DataGrid.Column<E> addGeneratedColumn(String columnId, GenericColumnGenerator<E, ?> generator) {
        io.jmix.ui.component.DataGrid.Column<E> column = getColumn(columnId);
        if (column == null) {
            throw new DevelopmentException("Unable to set ColumnGenerator for non-existing column: " + columnId);
        }

        Class<? extends Renderer> rendererType = null;

        Renderer renderer = column.getRenderer();
        if (renderer != null) {
            Class<?>[] rendererInterfaces = renderer.getClass().getInterfaces();

            rendererType = (Class<? extends Renderer>) Arrays.stream(rendererInterfaces)
                    .filter(Renderer.class::isAssignableFrom)
                    .findFirst()
                    .orElseThrow(() ->
                            new DevelopmentException(
                                    "Renderer should be specified explicitly for generated column: " + columnId));
        }


        io.jmix.ui.component.DataGrid.Column<E> generatedColumn = addGeneratedColumn(columnId, new ColumnGenerator<E, Object>() {
            @Override
            public Object getValue(ColumnGeneratorEvent<E> event) {
                return generator.getValue(event);
            }

            @Override
            public Class<Object> getType() {
                return ((DataGrid.Column) column).getGeneratedType();
            }
        });

        if (renderer != null) {
            generatedColumn.setRenderer(createRenderer(rendererType));
        }

        return column;
    }

    @Override
    public <T extends Renderer> T createRenderer(Class<T> type) {
        return this.applicationContext.getBean(type);
    }

    @Override
    protected ValueProvider getDefaultPresentationValueProvider(io.jmix.ui.component.DataGrid.Column<E> column) {
        MetaProperty metaProperty = column.getPropertyPath() != null
                ? column.getPropertyPath().getMetaProperty()
                : null;

        if (column instanceof DataGrid.Column && ((DataGrid.Column<E>) column).getFormatter() != null) {
            //noinspection unchecked
            return new FormatterBasedValueProvider<>(((DataGrid.Column<E>) column).getFormatter());
        } else if (metaProperty != null) {
            if (Collection.class.isAssignableFrom(metaProperty.getJavaType())) {
                return new FormatterBasedValueProvider<>(this.applicationContext.getBean(CollectionFormatter.class));
            }
            if (column instanceof DataGrid.Column
                    && ((DataGrid.Column<E>) column).getType() == Boolean.class) {
                return new YesNoIconPresentationValueProvider();
            }
        }

        return new StringPresentationValueProvider(metaProperty, metadataTools);
    }

    @Override
    protected com.vaadin.ui.renderers.Renderer getDefaultRenderer(io.jmix.ui.component.DataGrid.Column<E> column) {
        MetaProperty metaProperty = column.getPropertyPath() != null
                ? column.getPropertyPath().getMetaProperty()
                : null;

        return column instanceof DataGrid.Column
                && ((DataGrid.Column<E>) column).getType() == Boolean.class
                && metaProperty != null
                ? new com.vaadin.ui.renderers.HtmlRenderer()
                : new com.vaadin.ui.renderers.TextRenderer();
    }

    protected LegacySettingsDelegate createSettingsDelegate() {
        return (LegacySettingsDelegate) this.applicationContext.getBean(LegacySettingsDelegate.NAME,
                this, new LegacyDataGridSettingsConverter(), getSettingsBinder());
    }

    @Override
    public void setItems(@Nullable DataGridItems<E> dataGridItems) {
        super.setItems(dataGridItems);

        if (getRowsCount() != null) {
            getRowsCount().setRowsCountTarget(this);
        }

        initShowEntityInfoAction();
    }

    protected void initShowEntityInfoAction() {
        UiShowEntityInfoContext showInfoContext = new UiShowEntityInfoContext();
        accessManager.applyRegisteredConstraints(showInfoContext);

        if (showInfoContext.isPermitted()) {
            if (getAction(ShowEntityInfoAction.ID) == null) {
                addAction(actions.create(ShowEntityInfoAction.ID));
            }
        }
    }

    @Nullable
    @Override
    public RowsCount getRowsCount() {
        return dataGridDelegate.getRowsCount();
    }

    @Override
    public void setRowsCount(@Nullable RowsCount rowsCount) {
        dataGridDelegate.setRowsCount(
                rowsCount,
                topPanel,
                this::createTopPanel,
                componentComposition,
                this::updateCompositionStylesTopPanelVisible,
                this);
    }

    protected DataGridDelegate createDataGridDelegate() {
        return (DataGridDelegate) this.applicationContext.getBean(DataGridDelegate.NAME);
    }

    @Nullable
    @Override
    public Object getEditedItemId() {
        E item = getEditedItem();
        return item != null ? EntityValues.getId(item) : null;
    }

    @Override
    public void editItem(Object itemId) {
        checkNotNullArgument(itemId, "Item's Id must be non null");

        DataGridItems<E> dataGridItems = getItems();
        if (dataGridItems == null
                || dataGridItems.getState() == BindingState.INACTIVE) {
            return;
        }

        E item = getItems().getItem(itemId);
        edit(item);
    }

    @Override
    public void removeEditorPreCommitListener(Consumer<EditorPreCommitEvent> listener) {
        internalRemoveEditorPreCommitListener(listener);
    }

    @Override
    public void removeEditorPostCommitListener(Consumer<EditorPostCommitEvent> listener) {
        internalRemoveEditorPostCommitListener(listener);
    }

    @Override
    public void removeEditorCloseListener(Consumer<EditorCloseEvent> listener) {
        internalRemoveEditorCloseListener(listener);
    }

    @Override
    public void removeEditorOpenListener(Consumer<EditorOpenEvent> listener) {
        internalRemoveEditorOpenListener(listener);
    }

    @Override
    public void removeColumnCollapsingChangeListener(Consumer<ColumnCollapsingChangeEvent> listener) {
        internalRemoveColumnCollapsingChangeListener(listener);
    }

    @Override
    public void removeColumnReorderListener(Consumer<ColumnReorderEvent> listener) {
        unsubscribe(ColumnReorderEvent.class, listener);
    }

    @Override
    public void removeColumnResizeListener(Consumer<ColumnResizeEvent> listener) {
        internalRemoveColumnResizeListener(listener);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void removeSelectionListener(Consumer<SelectionEvent<E>> listener) {
        unsubscribe(SelectionEvent.class, (Consumer) listener);
    }

    @Override
    public void removeSortListener(Consumer<SortEvent> listener) {
        unsubscribe(SortEvent.class, listener);
    }

    @Override
    public void removeContextClickListener(Consumer<ContextClickEvent> listener) {
        internalRemoveContextClickListener(listener);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void removeItemClickListener(Consumer<ItemClickEvent<E>> listener) {
        unsubscribe(ItemClickEvent.class, (Consumer) listener);
    }

    @Override
    public void addCellStyleProvider(CellStyleProvider<? super E> styleProvider) {
        if (this.cellStyleProviders == null) {
            this.cellStyleProviders = new ArrayList<>();
        }

        if (!this.cellStyleProviders.contains(styleProvider)) {
            this.cellStyleProviders.add(styleProvider);

            repaint();
        }
    }

    @Override
    public void removeCellStyleProvider(CellStyleProvider<? super E> styleProvider) {
        if (this.cellStyleProviders != null) {
            if (this.cellStyleProviders.remove(styleProvider)) {
                repaint();
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public CellDescriptionProvider<E> getCellDescriptionProvider() {
        return (CellDescriptionProvider<E>) cellDescriptionProvider;
    }

    @Override
    public void setCellDescriptionProvider(@Nullable CellDescriptionProvider<? super E> provider) {
        this.cellDescriptionProvider = provider;
        repaint();
    }

    @Nullable
    @Override
    protected String getGeneratedCellStyle(E item, io.jmix.ui.component.DataGrid.Column<E> column) {
        StringBuilder joinedStyle = null;

        if (column.getStyleProvider() != null) {
            String styleName = column.getStyleProvider().apply(item);
            if (styleName != null) {
                joinedStyle = new StringBuilder(styleName);
            }
        }

        if (cellStyleProviders != null) {
            for (CellStyleProvider<? super E> styleProvider : cellStyleProviders) {
                String styleName = styleProvider.getStyleName(item, column.getId());
                if (styleName != null) {
                    if (joinedStyle == null) {
                        joinedStyle = new StringBuilder(styleName);
                    } else {
                        joinedStyle.append(" ").append(styleName);
                    }
                }
            }
        }

        return joinedStyle != null ? joinedStyle.toString() : null;
    }

    @Nullable
    @Override
    protected String getGeneratedCellDescription(E item, io.jmix.ui.component.DataGrid.Column<E> column) {
        if (column.getDescriptionProvider() != null) {
            String cellDescription = column.getDescriptionProvider().apply(item);
            return ((AbstractDataGrid.ColumnImpl) column).getDescriptionContentMode() == ContentMode.HTML
                    ? sanitize(cellDescription)
                    : cellDescription;
        }

        if (cellDescriptionProvider != null) {
            return cellDescriptionProvider.getDescription(item, column.getId());
        }

        return null;
    }

    @Override
    @Nullable
    protected ValueProvider getColumnPresentationValueProvider(io.jmix.ui.component.DataGrid.Column<E> column) {
        Function presentationProvider = column.getPresentationProvider();
        Converter<?, ?> converter = null;
        Formatter<?> formatter = null;
        if (column instanceof DataGrid.Column) {
            converter = ((DataGrid.Column<E>) column).getConverter();
            formatter = ((DataGrid.Column<E>) column).getFormatter();
        }
        Renderer renderer = column.getRenderer();
        // The following priority is used to determine a value provider:
        // a presentation provider > a converter > a formatter > a renderer's presentation provider >
        // a value provider that always returns its input argument > a default presentation provider
        return presentationProvider != null
                ? (ValueProvider) presentationProvider::apply
                : converter != null
                ? new DataGridConverterBasedValueProvider(converter)
                : formatter != null
                ? new FormatterBasedValueProvider(formatter)
                : renderer != null && ((AbstractRenderer) renderer).getPresentationValueProvider() != null
                ? ((AbstractRenderer) renderer).getPresentationValueProvider()
                : renderer != null
                // In case renderer != null and there are no other user specified value providers
                // We use a value provider that always returns its input argument instead of a default
                // value provider as we want to keep the original value type.
                ? ValueProvider.identity()
                : getDefaultPresentationValueProvider(column);
    }

    @Override
    protected void copyColumnProperties(io.jmix.ui.component.DataGrid.Column<E> column, io.jmix.ui.component.DataGrid.Column<E> existingColumn) {
        super.copyColumnProperties(column, existingColumn);

        if (column instanceof DataGrid.Column && existingColumn instanceof DataGrid.Column) {
            ((DataGrid.Column<E>) column).setFormatter(((DataGrid.Column<E>) existingColumn).getFormatter());
        }
    }

    @Override
    protected JmixGridEditorFieldFactory<E> createEditorFieldFactory() {
        DataGridEditorFieldFactory fieldFactory =
                this.applicationContext.getBean(DataGridEditorFieldFactory.class);
        return new DataGridEditorFieldFactoryAdapter<>(this, fieldFactory);
    }

    protected Datasource createItemDatasource(E item) {
        if (itemDatasources == null) {
            itemDatasources = new WeakHashMap<>();
        }

        Object fieldDatasource = itemDatasources.get(item);
        if (fieldDatasource instanceof Datasource) {
            return (Datasource) fieldDatasource;
        }

        EntityDataGridItems<E> items = getEntityDataGridItemsNN();
        Datasource datasource = DsBuilder.create()
                .setAllowCommit(false)
                .setMetaClass(items.getEntityMetaClass())
                .setRefreshMode(CollectionDatasource.RefreshMode.NEVER)
                .setViewName(View.LOCAL)
                .buildDatasource();

        ((DatasourceImplementation) datasource).valid();

        //noinspection unchecked
        datasource.setItem(item);

        return datasource;
    }

    @Override
    protected void updateAggregationRow() {
        boolean isAggregatable = isAggregatable()
                && (getItems() instanceof ContainerDataGridItems || getItems() instanceof AggregatableDataGridItems);
        if (isAggregatable) {
            Map<String, String> results = __aggregate();
            fillAggregationRow(results);
        } else {
            removeAggregationRow();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    protected Map<String, String> __aggregate() {
        if (getItems() instanceof AggregatableDataGridItems) {
            List<AggregationInfo> aggregationInfos = getAggregationInfos();
            Map<AggregationInfo, String> aggregationInfoMap = ((AggregatableDataGridItems) getItems()).aggregate(
                    aggregationInfos.toArray(new AggregationInfo[0]),
                    getItems().getItems().map(EntityValues::getId).collect(Collectors.toList())
            );

            return convertAggregationKeyMapToColumnIdKeyMap(aggregationInfoMap);
        } else {
            return super.__aggregate();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    protected Map<String, Object> __aggregateValues() {
        if (getItems() instanceof AggregatableDataGridItems) {
            List<AggregationInfo> aggregationInfos = getAggregationInfos();
            Map<AggregationInfo, Object> aggregationInfoMap = ((AggregatableDataGridItems) getItems()).aggregateValues(
                    aggregationInfos.toArray(new AggregationInfo[0]),
                    getItems().getItems().map(EntityValues::getId).collect(Collectors.toList())
            );

            return convertAggregationKeyMapToColumnIdKeyMap(aggregationInfoMap);
        } else {
            return super.__aggregateValues();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public void removeLookupValueChangeListener(Consumer<LookupSelectionChangeEvent<E>> listener) {
        unsubscribe(LookupSelectionChangeEvent.class, (Consumer) listener);
    }

    @Override
    public void setDetailsGenerator(@Nullable DetailsGenerator<E> detailsGenerator) {
        super.setDetailsGenerator(detailsGenerator);
    }

    @Override
    protected Collection<MetaPropertyPath> getAutowiredProperties(EntityDataGridItems<E> entityDataGridSource) {
        if (entityDataGridSource instanceof DatasourceDataUnit) {
            CollectionDatasource datasource = ((DatasourceDataUnit) entityDataGridSource).getDatasource();

            return datasource.getView() != null ?
                    // if a view is specified - use view properties
                    metadataTools.getFetchPlanPropertyPaths(datasource.getView(), datasource.getMetaClass()) :
                    // otherwise use all properties from meta-class
                    metadataTools.getPropertyPaths(datasource.getMetaClass());
        }

        return super.getAutowiredProperties(entityDataGridSource);
    }

    @Override
    protected void detachItemContainer(Object container) {
        if (container instanceof Datasource) {
            Datasource<?> datasource = (Datasource<?>) container;
            datasource.setItem(null);
        } else {
            super.detachItemContainer(container);
        }
    }

    protected static class ColumnImpl<E extends Entity>
            extends AbstractDataGrid.ColumnImpl<E>
            implements DataGrid.Column<E> {

        protected final Class type;
        protected Class generatedType;
        protected Converter converter;
        protected Formatter formatter;
        protected ColumnEditorFieldGenerator fieldGenerator;

        public ColumnImpl(String id, @Nullable MetaPropertyPath propertyPath, AbstractDataGrid<?, E> owner) {
            this(id, propertyPath, propertyPath != null ? propertyPath.getRangeJavaClass() : String.class, owner);
        }

        public ColumnImpl(String id, Class type, AbstractDataGrid<?, E> owner) {
            this(id, null, type, owner);
        }

        protected ColumnImpl(String id, @Nullable MetaPropertyPath propertyPath, Class type, AbstractDataGrid<?, E> owner) {
            super(id, propertyPath, owner);
            this.type = type;
        }

        @Override
        public Class getType() {
            return type;
        }

        @Override
        public void setGeneratedType(Class generatedType) {
            this.generatedType = generatedType;
        }

        @Override
        public Class getGeneratedType() {
            return generatedType;
        }

        @Nullable
        @Override
        public Converter<?, ?> getConverter() {
            return converter;
        }

        @Override
        public void setConverter(@Nullable Converter<?, ?> converter) {
            this.converter = converter;
            updateRendererInternal();
        }

        @Nullable
        @Override
        public Formatter getFormatter() {
            return formatter;
        }

        @Override
        public void setFormatter(@Nullable Formatter formatter) {
            this.formatter = formatter;
            updateRendererInternal();
        }

        @Nullable
        @Override
        public ColumnEditorFieldGenerator getEditorFieldGenerator() {
            return fieldGenerator;
        }

        @Override
        public void setEditorFieldGenerator(@Nullable ColumnEditorFieldGenerator fieldFactory) {
            this.fieldGenerator = fieldFactory;
            updateEditable();
        }

        @Override
        public boolean isShouldBeEditable() {
            return editable
                    && propertyPath != null  // We can't generate field for editor in case we don't have propertyPath
                    && (!generated && !isRepresentsCollection()
                    || fieldGenerator != null
                    || generator != null)
                    && isEditingPermitted();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected static class DataGridEditorFieldFactoryAdapter<E extends Entity>
            extends AbstractDataGrid.DataGridEditorFieldFactoryAdapter {

        public DataGridEditorFieldFactoryAdapter(AbstractDataGrid dataGrid, DataGridEditorFieldFactory fieldFactory) {
            super(dataGrid, fieldFactory);
        }

        @Override
        protected Field createField(AbstractDataGrid.ColumnImpl column, Object bean) {
            if (column instanceof ColumnImpl && ((ColumnImpl) column).getEditorFieldGenerator() != null) {
                String fieldPropertyId = String.valueOf(column.getPropertyId());
                Datasource fieldDataSource = ((WebDataGrid) dataGrid).createItemDatasource((Entity) bean);
                return ((ColumnImpl) column).getEditorFieldGenerator().createField(fieldDataSource, fieldPropertyId);
            }

            return super.createField(column, bean);
        }
    }
}
