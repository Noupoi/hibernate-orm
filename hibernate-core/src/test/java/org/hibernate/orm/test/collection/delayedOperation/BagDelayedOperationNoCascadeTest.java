/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */

package org.hibernate.orm.test.collection.delayedOperation;

import java.util.ArrayList;
import java.util.List;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;

import org.hibernate.Hibernate;
import org.hibernate.Transaction;
import org.hibernate.collection.internal.AbstractPersistentCollection;

import org.hibernate.testing.TestForIssue;
import org.hibernate.testing.junit5.SessionFactoryBasedFunctionalTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests delayed operations that are queued for a PersistentBag. The Bag does not have
 * to be extra-lazy to queue the operations.
 *
 * @author Gail Badner
 */
public class BagDelayedOperationNoCascadeTest extends SessionFactoryBasedFunctionalTest {
	private Long parentId;

	@Override
	protected Class[] getAnnotatedClasses() {
		return new Class[] {
				Parent.class,
				Child.class
		};
	}

	@BeforeEach
	public void setup() {
		// start by cleaning up in case a test fails
		if ( parentId != null ) {
			cleanup();
		}

		Parent parent = new Parent();
		Child child1 = new Child( "Sherman" );
		Child child2 = new Child( "Yogi" );
		parent.addChild( child1 );
		parent.addChild( child2 );

		inTransaction(
				session -> {
					session.persist( child1 );
					session.persist( child2 );
					session.persist( parent );
				}
		);

		parentId = parent.getId();
	}

	@AfterAll
	public void cleanup() {
		inTransaction(
				session -> {
					session.createQuery( "delete from Child" ).executeUpdate();
					session.createQuery( "delete from Parent" ).executeUpdate();
				}
		);
		parentId = null;
	}

	@Test
	@TestForIssue(jiraKey = "HHH-5855")
	public void testSimpleAddManaged() {
		// Add 2 Child entities
		Child c1 = new Child( "Darwin" );
		Child c2 = new Child( "Comet" );
		inTransaction(
				session -> {
					session.persist( c1 );
					session.persist( c2 );
				}
		);

		// Add a managed Child and commit
		inTransaction(
				session -> {
					Parent p = session.get( Parent.class, parentId );
					assertFalse( Hibernate.isInitialized( p.getChildren() ) );
					// get the first Child so it is managed; add to collection
					p.addChild( session.get( Child.class, c1.getId() ) );
					// collection should still be uninitialized
					assertFalse( Hibernate.isInitialized( p.getChildren() ) );
				}
		);

		inTransaction(
				session -> {
					Parent p = session.get( Parent.class, parentId );
					assertFalse( Hibernate.isInitialized( p.getChildren() ) );
					assertEquals( 3, p.getChildren().size() );

				}
		);

		// Add the other managed Child, merge and commit.
		inTransaction(
				session -> {
					Parent p = session.get( Parent.class, parentId );
					assertFalse( Hibernate.isInitialized( p.getChildren() ) );
					// get the second Child so it is managed; add to collection
					p.addChild( session.get( Child.class, c2.getId() ) );
					// collection should still be uninitialized
					assertFalse( Hibernate.isInitialized( p.getChildren() ) );
					session.merge( p );

				}
		);

		inTransaction(
				session -> {
					Parent p = session.get( Parent.class, parentId );
					assertFalse( Hibernate.isInitialized( p.getChildren() ) );
					assertEquals( 4, p.getChildren().size() );
				}
		);
	}

	@Test
	@TestForIssue(jiraKey = "HHH-11209")
	public void testMergeInitializedBagAndRemerge() {
		final Parent parent = inTransaction(
				session -> {
					Parent p = session.get( Parent.class, parentId );
					assertFalse( Hibernate.isInitialized( p.getChildren() ) );
					// initialize
					Hibernate.initialize( p.getChildren() );
					assertTrue( Hibernate.isInitialized( p.getChildren() ) );
					return p;
				}
		);

		Parent savedParent = inTransaction(
				session -> {
					Parent p = (Parent) session.merge( parent );
					Child c = new Child( "Zeke" );
					c.setParent( p );
					session.persist( c );
					assertFalse( Hibernate.isInitialized( p.getChildren() ) );
					p.getChildren().size();
					p.getChildren().add( c );
					return p;
				}
		);

		// Merge detached Parent with initialized children
		Parent mergedParent =  inSession(
				session -> {
					Transaction transaction = session.beginTransaction();
					Parent p;
					try {
						p = (Parent) session.merge( savedParent );
						// after merging, p#children will be uninitialized
						assertFalse( Hibernate.isInitialized( p.getChildren() ) );
						assertTrue( ( (AbstractPersistentCollection) p.getChildren() ).hasQueuedOperations() );
						transaction.commit();
					}catch (Exception e){
						if(transaction.isActive()){
							transaction.rollback();
						}
						throw e;
					}
					assertFalse( ( (AbstractPersistentCollection) p.getChildren() ).hasQueuedOperations() );
					return p;
				}
		);

		// Merge detached Parent, now with uninitialized children no queued operations
		inTransaction(
				session -> {
					Parent p = (Parent) session.merge( mergedParent );
					assertFalse( Hibernate.isInitialized( p.getChildren() ) );
					assertFalse( ( (AbstractPersistentCollection) p.getChildren() ).hasQueuedOperations() );
				}
		);
	}

	@Entity(name = "Parent")
	public static class Parent {

		@Id
		@GeneratedValue(strategy = GenerationType.AUTO)
		private Long id;

		// Don't need extra-lazy to delay add operations to a bag.
		@OneToMany(mappedBy = "parent")
		private List<Child> children = new ArrayList<>();

		public Parent() {
		}

		public Long getId() {
			return id;
		}

		public void setId(Long id) {
			this.id = id;
		}

		public List<Child> getChildren() {
			return children;
		}

		public void addChild(Child child) {
			children.add( child );
			child.setParent( this );
		}
	}

	@Entity(name = "Child")
	public static class Child {

		@Id
		@GeneratedValue(strategy = GenerationType.AUTO)
		private Long id;

		@Column(nullable = false)
		private String name;

		@ManyToOne
		private Parent parent;

		public Child() {
		}

		public Child(String name) {
			this.name = name;
		}

		public Long getId() {
			return id;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Parent getParent() {
			return parent;
		}

		public void setParent(Parent parent) {
			this.parent = parent;
		}

		@Override
		public String toString() {
			return "Child{" +
					"id=" + id +
					", name='" + name + '\'' +
					'}';
		}

		@Override
		public boolean equals(Object o) {
			if ( this == o ) {
				return true;
			}
			if ( o == null || getClass() != o.getClass() ) {
				return false;
			}

			Child child = (Child) o;

			return name.equals( child.name );

		}

		@Override
		public int hashCode() {
			return name.hashCode();
		}
	}

}